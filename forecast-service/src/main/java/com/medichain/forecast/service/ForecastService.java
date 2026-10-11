package com.medichain.forecast.service;

import com.medichain.forecast.dto.ForecastRequest;
import com.medichain.forecast.dto.ForecastRequest.*;
import com.medichain.forecast.dto.ForecastResponse;
import com.medichain.forecast.dto.ForecastResponse.*;
import com.medichain.forecast.ml.DemandPredictionModel;
import com.medichain.forecast.ml.ModelEvaluator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Runs the whole AI pipeline, step by step:
 *
 *   1. Forecast demand for every facility x medicine        (ml.DemandPredictionModel)
 *   2. Stock-out risk: days to stock-out, chance, level     (StockOutRiskService)
 *   3. Redistribution: transfers from surplus to deficit    (RedistributionService)
 *   4. Procurement: what to order, how much, by when        (ProcurementService)
 *   5. Expiry risk: batches that will expire unused         (ExpiryRiskService)
 *   6. Plain-English summary
 */
@Service
public class ForecastService {

    private final StockOutRiskService riskService;
    private final RedistributionService redistributionService;
    private final ProcurementService procurementService;
    private final ExpiryRiskService expiryService;

    public ForecastService(StockOutRiskService riskService, RedistributionService redistributionService,
                           ProcurementService procurementService, ExpiryRiskService expiryService) {
        this.riskService = riskService;
        this.redistributionService = redistributionService;
        this.procurementService = procurementService;
        this.expiryService = expiryService;
    }

    public ForecastResponse analyse(ForecastRequest req) {
        LocalDate asOf = req.asOf();
        int horizon = Math.max(1, req.horizonDays());

        // ---- 1. forecast + 2. stock simulation for every facility x medicine
        List<FacilityMedicine> items = buildItems(req);
        for (FacilityMedicine it : items) riskService.simulate(it, asOf);
        List<RiskRow> risks = new ArrayList<>();
        for (FacilityMedicine it : items) risks.add(riskService.assess(it, horizon, asOf, req.district()));
        risks.sort(Comparator.comparing((RiskRow r) -> r.daysToStockout() == null ? Integer.MAX_VALUE : r.daysToStockout()));

        // ---- 3. transfers, medicine by medicine
        List<TransferRow> transfers = new ArrayList<>();
        Map<Long, List<FacilityMedicine>> byMedicine = items.stream()
                .collect(Collectors.groupingBy(it -> it.medicine.id(), LinkedHashMap::new, Collectors.toList()));
        for (List<FacilityMedicine> group : byMedicine.values()) {
            transfers.addAll(redistributionService.recommend(group, horizon, asOf));
        }
        transfers.sort(Comparator.comparing(TransferRow::daysOfStockBefore));

        // ---- 4. purchase orders (after transfers)
        List<OrderRow> orders = new ArrayList<>();
        for (FacilityMedicine it : items) {
            OrderRow o = procurementService.suggest(it, asOf);
            if (o != null) orders.add(o);
        }
        orders.sort(Comparator.comparing(OrderRow::orderBy));

        // ---- 5. expiry
        List<ExpiryRow> expiry = expiryService.find(items, transfers, asOf);

        // ---- 6. summary
        List<String> summary = summary(risks, transfers, orders, expiry, horizon);
        String method = "Demand forecast = blend of Holt exponential smoothing (time series, parameters learned per facility "
                + "and medicine) and a regression on district disease cases. Days to stock-out = day-by-day simulation of "
                + "each batch (first-expiry-first-out). Transfers = most urgent facility first, nearest safe surplus, "
                + "expiring stock preferred. Orders = forecast x lead time + safety stock.";
        return new ForecastResponse(req.district(), asOf, horizon, summary, risks, transfers, orders, expiry, method);
    }

    /** Turns the request lists into one FacilityMedicine per facility x medicine, with its forecast. */
    private List<FacilityMedicine> buildItems(ForecastRequest req) {
        LocalDate asOf = req.asOf();
        LocalDate start = req.consumption().stream().map(ConsumptionData::date)
                .min(Comparator.naturalOrder()).orElse(asOf.minusDays(28));
        int days = (int) Math.max(1, ChronoUnit.DAYS.between(start, asOf));

        Map<String, double[]> history = new HashMap<>();
        for (ConsumptionData c : req.consumption()) {
            int i = (int) ChronoUnit.DAYS.between(start, c.date());
            if (i >= 0 && i < days) {
                history.computeIfAbsent(c.facilityId() + "-" + c.medicineId(), k -> new double[days])[i] += c.quantity();
            }
        }
        Map<String, Map<LocalDate, Integer>> casesByDisease = new HashMap<>();
        for (CaseData c : req.diseaseCases()) {
            casesByDisease.computeIfAbsent(c.disease(), k -> new TreeMap<>()).merge(c.weekStart(), c.cases(), Integer::sum);
        }

        List<FacilityMedicine> items = new ArrayList<>();
        for (FacilityData f : req.facilities()) {
            for (MedicineData m : req.medicines()) {
                FacilityMedicine it = new FacilityMedicine();
                it.facility = f;
                it.medicine = m;
                it.historyStart = start;
                it.demandHistory = history.getOrDefault(f.id() + "-" + m.id(), new double[days]);
                for (BatchData b : req.batches()) {
                    if (b.facilityId().equals(f.id()) && b.medicineId().equals(m.id())) it.batches.add(b);
                }
                Map<LocalDate, Integer> cases = casesByDisease.getOrDefault(m.diseaseTag(), Map.of());
                it.prediction = DemandPredictionModel.predict(start, it.demandHistory, cases, asOf, StockOutRiskService.SIMULATION_DAYS);
                it.backtestErrorPct = ModelEvaluator.backtestErrorPct(start, it.demandHistory, cases, asOf);
                items.add(it);
            }
        }
        return items;
    }

    private List<String> summary(List<RiskRow> risks, List<TransferRow> transfers, List<OrderRow> orders,
                                 List<ExpiryRow> expiry, int horizon) {
        List<String> lines = new ArrayList<>();
        List<RiskRow> atRisk = risks.stream().filter(RiskRow::runsOutInHorizon).toList();
        if (atRisk.isEmpty()) {
            lines.add("No facility is expected to run out of these medicines in the next " + horizon + " days.");
        }
        for (RiskRow r : atRisk) {
            List<TransferRow> in = transfers.stream()
                    .filter(t -> t.toFacilityId().equals(r.facilityId()) && t.medicineId().equals(r.medicineId())).toList();
            OrderRow order = orders.stream()
                    .filter(o -> o.facilityId().equals(r.facilityId()) && o.medicineId().equals(r.medicineId()))
                    .findFirst().orElse(null);
            String action;
            if (!in.isEmpty()) {
                action = "transfer " + in.stream()
                        .map(t -> String.format("%,d %ss from %s (%.0f km away)", t.quantity(), t.unit(), t.fromFacility(), t.distanceKm()))
                        .collect(Collectors.joining(" and "));
            } else if (order != null && order.urgency().equals("URGENT")) {
                action = String.format("no nearby surplus - place an URGENT order of %,d", order.orderQuantity());
            } else if (order != null) {
                action = String.format("order %,d by %s; it arrives before the stock runs out", order.orderQuantity(), order.orderBy());
            } else {
                action = "include it in the next routine order";
            }
            lines.add(String.format("%s will run out of %s in %d day(s) (%s). Recommended: %s.",
                    r.facilityName(), r.medicineName(), r.daysToStockout(), r.stockoutDate(), action));
        }
        if (!expiry.isEmpty()) {
            int units = expiry.stream().mapToInt(ExpiryRow::unitsAtRisk).sum();
            int rescued = transfers.stream().mapToInt(TransferRow::expiringUnitsRescued).sum();
            lines.add(String.format("%,d units are likely to expire before use; the transfers above save %,d of them.", units, rescued));
        }
        long soon = orders.stream().filter(o -> !o.urgency().equals("PLANNED")).count();
        if (soon > 0) lines.add(soon + " purchase order(s) should be placed this week.");
        return lines;
    }
}
