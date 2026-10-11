package com.medichain.forecast.service;

import com.medichain.forecast.config.ForecastConfig;
import com.medichain.forecast.dto.ForecastRequest.BatchData;
import com.medichain.forecast.dto.ForecastRequest.FacilityData;
import com.medichain.forecast.dto.ForecastResponse.TransferRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Redistribution = move stock from facilities with SURPLUS to facilities that will run out.
 *
 * Rules (a simple greedy optimisation, easy to explain):
 *  1. A facility is a DEFICIT if it runs out within the horizon or before a new order could arrive.
 *     It needs enough stock for (lead time + buffer) days.
 *  2. A facility is a DONOR only if it is itself safe; it always keeps (lead time + buffer) days
 *     of demand plus safety stock. Only batches with at least 15 days of shelf life are sent.
 *  3. Most urgent deficit first. Donors are ranked by distance; a donor with stock that would
 *     otherwise expire counts as 40% closer (so expiring stock gets used instead of wasted).
 */
@Service
public class RedistributionService {

    private static final int MIN_SHELF_LIFE_DAYS = 15;

    private final ForecastConfig config;

    public RedistributionService(ForecastConfig config) {
        this.config = config;
    }

    /** @param items all facilities for ONE medicine */
    public List<TransferRow> recommend(List<FacilityMedicine> items, int horizon, LocalDate asOf) {
        List<TransferRow> transfers = new ArrayList<>();
        int lead = items.get(0).medicine.leadTimeDays();
        int keepDays = lead + config.bufferDays;

        // ---- 1. how much can each facility spare?
        for (FacilityMedicine it : items) {
            it.expiringLeft = new HashMap<>(it.unitsExpiring);
            boolean safe = it.daysToStockout == null || it.daysToStockout > keepDays + 14;
            if (!safe) continue;
            double keep = it.prediction.total(keepDays) + config.serviceLevelZ * it.dailyErrorStd() * Math.sqrt(lead);
            int movable = 0;
            for (BatchData b : sortedByExpiry(it.batches)) {
                if (b.quantity() > 0 && !b.expiryDate().isBefore(asOf.plusDays(MIN_SHELF_LIFE_DAYS))) {
                    it.movableLeft.put(b.batchNo(), b.quantity());
                    movable += b.quantity();
                }
            }
            it.surplus = (int) Math.max(0, Math.min(it.usableStock - keep, movable));
        }

        // ---- 2. who needs stock? most urgent first
        List<FacilityMedicine> deficits = items.stream()
                .filter(it -> it.daysToStockout != null && it.daysToStockout <= Math.max(horizon, lead))
                .sorted(Comparator.comparing(it -> it.daysToStockout))
                .toList();

        for (FacilityMedicine to : deficits) {
            double target = to.prediction.total(keepDays);
            double usable = to.usableStock - StockOutRiskService.expiringBefore(to, asOf.plusDays(keepDays));
            int need = (int) Math.ceil((target - usable) / 10.0) * 10;

            // ---- 3. nearest donors (expiring stock preferred)
            List<FacilityMedicine> donors = items.stream()
                    .filter(d -> d != to && d.surplus >= 10 && distanceKm(d.facility, to.facility) <= config.maxTransferKm)
                    .sorted(Comparator.comparing(d -> distanceKm(d.facility, to.facility) * (d.totalExpiring() > 0 ? 0.6 : 1.0)))
                    .toList();

            for (FacilityMedicine from : donors) {
                if (need <= 0) break;
                int qty = Math.min(need, from.surplus);
                if (qty >= 10) qty = qty / 10 * 10;

                // take donor batches first-expiry-first-out
                List<BatchData> moved = new ArrayList<>();
                int left = qty, rescued = 0;
                for (Map.Entry<String, Integer> e : from.movableLeft.entrySet()) {
                    if (left <= 0) break;
                    int take = Math.min(left, e.getValue());
                    if (take <= 0) continue;
                    e.setValue(e.getValue() - take);
                    left -= take;
                    moved.add(new BatchData(to.facility.id(), to.medicine.id(), e.getKey(), take, from.expiryOf(e.getKey())));
                    int stillExpiring = from.expiringLeft.getOrDefault(e.getKey(), 0);
                    int r = Math.min(take, stillExpiring);
                    if (r > 0) {
                        from.expiringLeft.put(e.getKey(), stillExpiring - r);
                        rescued += r;
                    }
                }
                from.expiringRescued += rescued;
                from.surplus -= qty;
                from.outgoing += qty;
                to.incoming += qty;
                need -= qty;

                // effect on the receiver: simulate again with the extra batches
                List<BatchData> after = new ArrayList<>(to.batches);
                after.addAll(moved);
                Integer daysAfter = StockOutRiskService.simulate(after, to.prediction.daily(), asOf).daysToStockout();
                double km = distanceKm(from.facility, to.facility);
                double donorPerDay = Math.max(0.1, from.prediction.average(30));
                String unit = to.medicine.unit();

                String rationale = String.format(
                        "%s will run out of %s in %d day(s) (%,d %ss left, forecast %.0f/day). "
                                + "%s is %.1f km away and holds %,d %ss (about %d days of its own use). "
                                + "After keeping %d days of stock plus safety stock it can spare %,d. "
                                + "This raises %s's stock from %d to %s days.",
                        to.facility.name(), to.medicine.name(), to.daysToStockout, to.usableStock, unit,
                        to.prediction.average(horizon), from.facility.name(), km, from.usableStock, unit,
                        (int) (from.usableStock / donorPerDay), keepDays, qty, to.facility.name(), to.daysToStockout,
                        daysAfter == null ? StockOutRiskService.SIMULATION_DAYS + "+" : String.valueOf(daysAfter));
                if (rescued > 0) {
                    rationale += String.format(" It also uses %,d units that would otherwise expire at %s.", rescued, from.facility.name());
                }
                transfers.add(new TransferRow(from.facility.id(), from.facility.name(), to.facility.id(), to.facility.name(),
                        to.medicine.id(), to.medicine.name(), unit, qty, km, to.daysToStockout, daysAfter,
                        to.daysToStockout <= 7 ? "CRITICAL" : "HIGH", rescued, rationale));
            }
        }
        return transfers;
    }

    /** Approximate road distance: straight-line (haversine) distance x 1.3. */
    public static double distanceKm(FacilityData a, FacilityData b) {
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(a.latitude()))
                * Math.cos(Math.toRadians(b.latitude())) * Math.pow(Math.sin(dLon / 2), 2);
        double straightKm = 2 * 6371 * Math.asin(Math.sqrt(h));
        return Math.round(straightKm * 1.3 * 10) / 10.0;
    }

    private static List<BatchData> sortedByExpiry(List<BatchData> batches) {
        List<BatchData> list = new ArrayList<>(batches);
        list.sort(Comparator.comparing(BatchData::expiryDate));
        return list;
    }
}
