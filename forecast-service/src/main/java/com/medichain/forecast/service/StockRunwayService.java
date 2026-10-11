package com.medichain.forecast.service;

import com.medichain.forecast.dto.ForecastRequest;
import com.medichain.forecast.dto.ForecastRequest.*;
import com.medichain.forecast.dto.StockRunwayDto.*;
import com.medichain.forecast.ml.DemandPredictionModel;
import com.medichain.forecast.ml.ModelEvaluator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * AI Stock Duration & Depletion Predictor Service.
 * Simulates day-by-day First-Expiry-First-Out (FEFO) stock depletion against:
 *  1. Baseline time-series demand
 *  2. AI seasonal demand (epidemiological disease regression)
 *  3. High-surge epidemic outbreak wave
 */
@Service
public class StockRunwayService {

    public static final int TRAJECTORY_HORIZON_DAYS = 90;

    public DistrictRunwaySummary calculateDistrictSummary(ForecastRequest req) {
        LocalDate asOf = req.asOf();
        List<FacilityMedicine> items = buildItems(req);

        List<StockRunwayReport> reports = new ArrayList<>();
        int criticalCount = 0;
        int highCount = 0;

        for (FacilityMedicine item : items) {
            StockRunwayReport report = calculateRunwayReport(item, asOf);
            reports.add(report);
            if ("CRITICAL".equals(report.riskLevel())) criticalCount++;
            else if ("HIGH".equals(report.riskLevel())) highCount++;
        }

        // Sort by shortest runway first (most urgent depletion)
        reports.sort(Comparator.comparing(r -> r.daysRemaining() == null ? Integer.MAX_VALUE : r.daysRemaining()));

        return new DistrictRunwaySummary(req.district(), asOf, reports.size(), criticalCount, highCount, reports);
    }

    public StockRunwayReport calculateRunwayReport(FacilityMedicine item, LocalDate asOf) {
        double[] daily = item.prediction.daily();
        double[] baselineDaily = item.prediction.baselineDaily() != null ? item.prediction.baselineDaily() : daily;

        double[] surgeDaily = new double[daily.length];
        for (int i = 0; i < daily.length; i++) surgeDaily[i] = daily[i] * 1.35;

        // Run day-by-day FEFO simulation for all 3 scenarios
        SimResult simSeasonal = simulateTrajectory(item.batches, daily, asOf);
        SimResult simBaseline = simulateTrajectory(item.batches, baselineDaily, asOf);
        SimResult simSurge = simulateTrajectory(item.batches, surgeDaily, asOf);

        // Build 90-day trajectory points
        List<RunwayTrajectoryPoint> trajectory = new ArrayList<>();
        int maxDays = Math.min(TRAJECTORY_HORIZON_DAYS, daily.length);
        for (int d = 0; d < maxDays; d++) {
            trajectory.add(new RunwayTrajectoryPoint(
                    d,
                    asOf.plusDays(d),
                    simSeasonal.remainingStock[d],
                    simBaseline.remainingStock[d],
                    simSurge.remainingStock[d],
                    round1(daily[d]),
                    round1(baselineDaily[d])
            ));
        }

        // Key metrics
        Integer daysRemaining = simSeasonal.depletionDay;
        LocalDate depletionDate = daysRemaining != null ? asOf.plusDays(daysRemaining) : null;
        double runwayWeeks = daysRemaining != null ? round1(daysRemaining / 7.0) : 26.0;

        Integer baselineDays = simBaseline.depletionDay;
        LocalDate baselineDepletionDate = baselineDays != null ? asOf.plusDays(baselineDays) : null;
        Integer daysLostToSeason = (baselineDays != null && daysRemaining != null)
                ? Math.max(0, baselineDays - daysRemaining) : null;

        Integer surgeDays = simSurge.depletionDay;
        LocalDate surgeDepletionDate = surgeDays != null ? asOf.plusDays(surgeDays) : null;

        double baseDemand = round1(item.prediction.baselineAverage(30));
        double seasonalDemand = round1(item.prediction.average(30));
        double seasonalityMultiplier = baseDemand > 0 ? round2(seasonalDemand / baseDemand) : 1.0;
        double seasonalSurgePct = round1((seasonalityMultiplier - 1.0) * 100.0);

        int lead = item.medicine.leadTimeDays();
        String riskLevel = daysRemaining == null || daysRemaining > lead + 14 ? "LOW"
                : daysRemaining <= 7 ? "CRITICAL"
                : daysRemaining <= lead ? "HIGH" : "MEDIUM";

        // Historical consumption statistics
        int histLen = item.demandHistory.length;
        double avg14 = round1(item.prediction.avgLast14());
        double avg30 = round1(averageSubArray(item.demandHistory, histLen - 30, histLen));
        double avg60 = round1(averageSubArray(item.demandHistory, histLen - 60, histLen));
        double trendPct = item.prediction.avgPrev14() > 0
                ? round1(100.0 * (avg14 - item.prediction.avgPrev14()) / item.prediction.avgPrev14()) : 0.0;

        // Batch details & FEFO exhaustion schedule
        List<BatchDepletionDetail> batchDetails = buildBatchDetails(item.batches, simSeasonal, asOf);

        // AI insights
        List<String> insights = generateInsights(item, daysRemaining, depletionDate, runwayWeeks,
                baselineDays, daysLostToSeason, baseDemand, seasonalDemand, seasonalityMultiplier, seasonalSurgePct,
                simSeasonal.totalExpiring, lead, asOf);

        return new StockRunwayReport(
                item.facility.id(),
                item.facility.name(),
                item.medicine.id(),
                item.medicine.name(),
                item.medicine.unit(),
                item.medicine.diseaseTag(),
                friendlyDisease(item.medicine.diseaseTag()),
                asOf,
                item.usableStock,
                (int) item.batches.stream().filter(b -> b.quantity() > 0 && !b.expiryDate().isBefore(asOf)).count(),
                daysRemaining,
                depletionDate,
                runwayWeeks,
                riskLevel,
                baselineDays,
                baselineDepletionDate,
                daysLostToSeason,
                surgeDays,
                surgeDepletionDate,
                baseDemand,
                seasonalDemand,
                seasonalityMultiplier,
                seasonalSurgePct,
                round2(item.prediction.diseaseWeight()),
                round2(item.prediction.r2()),
                round1(item.prediction.casesChangeLast2WeeksPct()),
                round1(item.prediction.casesChangeNext2WeeksPct()),
                avg14,
                avg30,
                avg60,
                trendPct,
                trajectory,
                batchDetails,
                insights
        );
    }

    public StockRunwayReport simulateWhatIf(ForecastRequest req, Long facilityId, Long medicineId,
                                            int additionalStock, double surgeMultiplierOverride) {
        LocalDate asOf = req.asOf();
        List<FacilityMedicine> items = buildItems(req);
        FacilityMedicine target = items.stream()
                .filter(it -> it.facility.id().equals(facilityId) && it.medicine.id().equals(medicineId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Facility or Medicine not found"));

        // Copy batches and optionally add hypothetical buffer stock
        List<BatchData> adjustedBatches = new ArrayList<>(target.batches);
        if (additionalStock > 0) {
            adjustedBatches.add(new BatchData(target.facility.id(), target.medicine.id(),
                    "SIM-STOCK-ADD", additionalStock, asOf.plusMonths(24)));
        }

        // Adjust daily demand by user's surge override
        double[] baseDaily = target.prediction.daily();
        double[] adjustedDaily = new double[baseDaily.length];
        double multiplier = Math.max(0.1, surgeMultiplierOverride <= 0 ? 1.0 : surgeMultiplierOverride);
        for (int i = 0; i < baseDaily.length; i++) {
            adjustedDaily[i] = baseDaily[i] * multiplier;
        }

        FacilityMedicine simulatedItem = new FacilityMedicine();
        simulatedItem.facility = target.facility;
        simulatedItem.medicine = target.medicine;
        simulatedItem.historyStart = target.historyStart;
        simulatedItem.demandHistory = target.demandHistory;
        simulatedItem.batches = adjustedBatches;
        simulatedItem.usableStock = adjustedBatches.stream()
                .filter(b -> b.quantity() > 0 && !b.expiryDate().isBefore(asOf))
                .mapToInt(BatchData::quantity).sum();
        simulatedItem.prediction = new DemandPredictionModel.Prediction(
                adjustedDaily,
                target.prediction.baselineDaily(),
                target.prediction.errorStd(),
                target.prediction.alpha(),
                target.prediction.beta(),
                target.prediction.r2(),
                target.prediction.diseaseWeight(),
                target.prediction.casesChangeLast2WeeksPct(),
                target.prediction.casesChangeNext2WeeksPct(),
                target.prediction.avgLast14(),
                target.prediction.avgPrev14()
        );

        return calculateRunwayReport(simulatedItem, asOf);
    }

    // ---------------------------------------------------------------- Simulation Engine

    private record SimResult(
            Integer depletionDay,
            int[] remainingStock,
            int totalExpiring,
            Map<String, Integer> unitsExpiring,
            Map<String, Integer> unitsConsumed,
            Map<String, Integer> depletionDayPerBatch
    ) {}

    private static SimResult simulateTrajectory(List<BatchData> batches, double[] dailyDemand, LocalDate asOf) {
        List<BatchData> sorted = new ArrayList<>(batches);
        sorted.sort(Comparator.comparing(BatchData::expiryDate));

        List<String> names = new ArrayList<>();
        List<double[]> stock = new ArrayList<>();   // {remaining, expiryEpochDay, initial}
        for (BatchData b : sorted) {
            if (b.quantity() <= 0 || b.expiryDate().isBefore(asOf)) continue;
            names.add(b.batchNo());
            stock.add(new double[]{b.quantity(), b.expiryDate().toEpochDay(), b.quantity()});
        }

        int days = Math.min(TRAJECTORY_HORIZON_DAYS, dailyDemand.length);
        int[] remainingPerDay = new int[days];
        Map<String, Integer> expiring = new LinkedHashMap<>();
        Map<String, Integer> consumed = new LinkedHashMap<>();
        Map<String, Integer> batchDepletionDay = new LinkedHashMap<>();
        Integer stockoutDay = null;
        int totalExpiring = 0;

        for (int d = 0; d < days; d++) {
            long today = asOf.plusDays(d).toEpochDay();

            // 1. Expire outdated batches
            for (int i = 0; i < stock.size(); i++) {
                double[] s = stock.get(i);
                if (s[0] > 0 && s[1] < today) {
                    int wasted = (int) Math.round(s[0]);
                    expiring.merge(names.get(i), wasted, Integer::sum);
                    totalExpiring += wasted;
                    s[0] = 0;
                }
            }

            // 2. Consume demand from earliest-expiring batch first (FEFO)
            double need = dailyDemand[d];
            for (int i = 0; i < stock.size(); i++) {
                double[] s = stock.get(i);
                if (s[0] <= 0) continue;
                double take = Math.min(need, s[0]);
                s[0] -= take;
                consumed.merge(names.get(i), (int) Math.round(take), Integer::sum);
                need -= take;
                if (s[0] <= 0.01 && !batchDepletionDay.containsKey(names.get(i))) {
                    batchDepletionDay.put(names.get(i), d);
                }
                if (need <= 0.001) break;
            }

            if (need > 0.5 && stockoutDay == null) {
                stockoutDay = d;
            }

            // 3. Record remaining usable stock at end of day d
            double totalRemaining = 0;
            for (double[] s : stock) totalRemaining += s[0];
            remainingPerDay[d] = (int) Math.round(totalRemaining);
        }

        return new SimResult(stockoutDay, remainingPerDay, totalExpiring, expiring, consumed, batchDepletionDay);
    }

    private static List<BatchDepletionDetail> buildBatchDetails(List<BatchData> batches, SimResult sim, LocalDate asOf) {
        List<BatchDepletionDetail> list = new ArrayList<>();
        List<BatchData> sorted = new ArrayList<>(batches);
        sorted.sort(Comparator.comparing(BatchData::expiryDate));

        for (BatchData b : sorted) {
            if (b.quantity() <= 0) continue;
            int daysUntilExp = (int) ChronoUnit.DAYS.between(asOf, b.expiryDate());
            int unitsCon = sim.unitsConsumed.getOrDefault(b.batchNo(), 0);
            int unitsExp = sim.unitsExpiring.getOrDefault(b.batchNo(), 0);
            Integer depDay = sim.depletionDayPerBatch.get(b.batchNo());
            LocalDate depDate = depDay != null ? asOf.plusDays(depDay) : null;

            String status;
            if (daysUntilExp < 0) {
                status = "EXPIRED";
            } else if (unitsExp > 0) {
                status = "EXPIRES_BEFORE_USE";
            } else if (depDate != null) {
                status = "FULLY_CONSUMED";
            } else {
                status = "ACTIVE_BUFFER";
            }

            list.add(new BatchDepletionDetail(
                    b.batchNo(),
                    b.quantity(),
                    b.expiryDate(),
                    daysUntilExp,
                    unitsCon,
                    unitsExp,
                    depDate,
                    status
            ));
        }
        return list;
    }

    private List<String> generateInsights(FacilityMedicine item, Integer daysRemaining, LocalDate depletionDate,
                                          double runwayWeeks, Integer baselineDays, Integer daysLostToSeason,
                                          double baseDemand, double seasonalDemand, double seasonalityMultiplier,
                                          double seasonalSurgePct, int totalExpiring, int leadTimeDays, LocalDate asOf) {
        List<String> list = new ArrayList<>();
        String unit = item.medicine.unit();
        String med = item.medicine.name();
        String fac = item.facility.name();

        if (daysRemaining == null) {
            list.add(String.format("Safe Inventory: %s has %,d %ss of %s, lasting more than %d days.",
                    fac, item.usableStock, unit, med, TRAJECTORY_HORIZON_DAYS));
        } else {
            list.add(String.format("Predicted Depletion: Current stock of %,d %ss will last %d days (approx. %.1f weeks), depleting on %s.",
                    item.usableStock, unit, daysRemaining, runwayWeeks, depletionDate));
        }

        if (item.prediction.diseaseWeight() > 0) {
            list.add(String.format("Seasonal Epidemiology Effect: %s surveillance shows %+.0f%% caseload trend (R² = %.2f). This increases demand by %+.0f%% (%.1f -> %.1f %ss/day).",
                    friendlyDisease(item.medicine.diseaseTag()), item.prediction.casesChangeNext2WeeksPct(),
                    item.prediction.r2(), seasonalSurgePct, baseDemand, seasonalDemand, unit));
        } else if ("NONE".equals(item.medicine.diseaseTag())) {
            list.add(String.format("Non-Seasonal Baseline: Demand follows steady chronic usage at ~%.1f %ss/day.", baseDemand, unit));
        }

        if (daysLostToSeason != null && daysLostToSeason > 0) {
            list.add(String.format("Runway Impact: Seasonal disease acceleration exhausts stock %d days earlier than non-seasonal historical baseline (depletion moved from %s to %s).",
                    daysLostToSeason, asOf.plusDays(baselineDays), depletionDate));
        }

        if (daysRemaining != null && daysRemaining <= leadTimeDays) {
            list.add(String.format("⚠️ Procurement Lead Time Warning: Supplier lead time is %d days, but stock runs out in %d days. Immediate inter-facility redistribution is required.",
                    leadTimeDays, daysRemaining));
        }

        if (totalExpiring > 0) {
            list.add(String.format("⏳ Shelf-Life Risk: %,d %ss will expire before consumption under FIFO scheduling.",
                    totalExpiring, unit));
        }

        return list;
    }

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
                it.usableStock = it.batches.stream()
                        .filter(b -> b.quantity() > 0 && !b.expiryDate().isBefore(asOf))
                        .mapToInt(BatchData::quantity).sum();
                Map<LocalDate, Integer> cases = casesByDisease.getOrDefault(m.diseaseTag(), Map.of());
                it.prediction = DemandPredictionModel.predict(start, it.demandHistory, cases, asOf, StockOutRiskService.SIMULATION_DAYS);
                it.backtestErrorPct = ModelEvaluator.backtestErrorPct(start, it.demandHistory, cases, asOf);
                items.add(it);
            }
        }
        return items;
    }

    private static double averageSubArray(double[] arr, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(arr.length, to);
        if (to <= from) return 0;
        double s = 0;
        for (int i = from; i < to; i++) s += arr[i];
        return s / (to - from);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String friendlyDisease(String tag) {
        return switch (tag) {
            case "FEVER" -> "Fever & Vector-borne (Dengue, Malaria, Pyrexia)";
            case "DIARRHOEA" -> "Diarrhoeal & Water-borne Disease";
            case "ARI" -> "Acute Respiratory Infection (ARI)";
            default -> "Routine Non-Seasonal Care";
        };
    }
}
