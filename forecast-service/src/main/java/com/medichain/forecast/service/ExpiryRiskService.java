package com.medichain.forecast.service;

import com.medichain.forecast.config.ForecastConfig;
import com.medichain.forecast.dto.ForecastResponse.ExpiryRow;
import com.medichain.forecast.dto.ForecastResponse.TransferRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Expiry risk = batches that will still have units left on their expiry date.
 * The units come from the same FEFO stock simulation used for stock-out risk.
 * For each one we suggest an action: already rescued by a transfer, move it to a facility
 * that will use it in time, or issue it first.
 */
@Service
public class ExpiryRiskService {

    private final ForecastConfig config;

    public ExpiryRiskService(ForecastConfig config) {
        this.config = config;
    }

    public List<ExpiryRow> find(List<FacilityMedicine> items, List<TransferRow> transfers, LocalDate asOf) {
        List<ExpiryRow> rows = new ArrayList<>();
        for (FacilityMedicine it : items) {
            for (Map.Entry<String, Integer> e : it.unitsExpiring.entrySet()) {
                LocalDate expiry = it.expiryOf(e.getKey());
                int atRisk = e.getValue();
                int batchQty = it.batches.stream().filter(b -> b.batchNo().equals(e.getKey())).mapToInt(b -> b.quantity()).sum();

                String action;
                if (it.expiringRescued > 0) {
                    action = String.format("%,d units are already in the recommended transfers", it.expiringRescued);
                } else {
                    FacilityMedicine best = null;
                    double bestRoom = 0;
                    int daysLeft = (int) ChronoUnit.DAYS.between(asOf, expiry);
                    for (FacilityMedicine other : items) {
                        if (other == it || !other.medicine.id().equals(it.medicine.id())) continue;
                        if (RedistributionService.distanceKm(it.facility, other.facility) > config.maxTransferKm) continue;
                        // how much more could the other facility use before this batch expires?
                        double room = other.prediction.total(Math.max(1, daysLeft)) - other.usableStock - other.incoming;
                        if (room > bestRoom) { bestRoom = room; best = other; }
                    }
                    action = best != null
                            ? String.format("Send up to %,d to %s (%.1f km), which will use them before expiry",
                                (int) Math.min(bestRoom, atRisk), best.facility.name(),
                                RedistributionService.distanceKm(it.facility, best.facility))
                            : "Issue this batch first; return it to the district store if unused 30 days before expiry";
                }
                rows.add(new ExpiryRow(it.facility.id(), it.facility.name(), it.medicine.id(), it.medicine.name(),
                        e.getKey(), expiry, (int) ChronoUnit.DAYS.between(asOf, expiry), batchQty, atRisk,
                        Math.round(atRisk * it.medicine.unitPrice()), action));
            }
        }
        rows.sort(Comparator.comparing(ExpiryRow::expiryDate));
        return rows;
    }
}
