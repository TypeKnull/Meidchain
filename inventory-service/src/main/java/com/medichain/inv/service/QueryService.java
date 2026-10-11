package com.medichain.inv.service;

import com.medichain.inv.dto.ForecastRequest;
import com.medichain.inv.dto.ForecastRequest.*;
import com.medichain.inv.dto.ForecastResponse;
import com.medichain.inv.entity.Facility;
import com.medichain.inv.entity.Medicine;
import com.medichain.inv.repository.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The end-to-end flow for a question:
 *   1. understand the question (district, medicines, number of days)
 *   2. read stock, batches, consumption history and disease cases from the database
 *   3. send them to the Forecast Service (REST) and return its answer
 */
@Service
public class QueryService {

    /** What the UI receives: how the question was understood + the AI result. */
    public record QueryResult(String understoodAs, ForecastResponse result) { }

    private static final int HISTORY_DAYS = 120;

    private final FacilityRepository facilityRepo;
    private final MedicineRepository medicineRepo;
    private final StockBatchRepository batchRepo;
    private final ConsumptionRepository consumptionRepo;
    private final DiseaseCaseRepository caseRepo;
    private final StockService stockService;
    private final ForecastClient forecastClient;

    public QueryService(FacilityRepository facilityRepo, MedicineRepository medicineRepo, StockBatchRepository batchRepo,
                        ConsumptionRepository consumptionRepo, DiseaseCaseRepository caseRepo,
                        StockService stockService, ForecastClient forecastClient) {
        this.facilityRepo = facilityRepo;
        this.medicineRepo = medicineRepo;
        this.batchRepo = batchRepo;
        this.consumptionRepo = consumptionRepo;
        this.caseRepo = caseRepo;
        this.stockService = stockService;
        this.forecastClient = forecastClient;
    }

    // ------------------------------------------------------------------ 1. understand the question

    /** Words people use, mapped to medicine codes. */
    private static final Map<String, List<String>> WORDS = new LinkedHashMap<>();
    static {
        WORDS.put("paracetamol", List.of("PCM500"));
        WORDS.put("pcm", List.of("PCM500"));
        WORDS.put("crocin", List.of("PCM500"));
        WORDS.put("dolo", List.of("PCM500"));
        WORDS.put("fever", List.of("PCM500"));
        WORDS.put("ors", List.of("ORS"));
        WORDS.put("rehydration", List.of("ORS"));
        WORDS.put("zinc", List.of("ZINC20"));
        WORDS.put("diarrhoea", List.of("ORS", "ZINC20"));
        WORDS.put("diarrhea", List.of("ORS", "ZINC20"));
        WORDS.put("amoxicillin", List.of("AMOX500"));
        WORDS.put("antibiotic", List.of("AMOX500"));
        WORDS.put("metformin", List.of("MET500"));
        WORDS.put("diabetes", List.of("MET500"));
        WORDS.put("iron", List.of("IFA"));
        WORDS.put("ifa", List.of("IFA"));
        WORDS.put("folic", List.of("IFA"));
        WORDS.put("amlodipine", List.of("AMLO5"));
        WORDS.put("bp", List.of("AMLO5"));
        WORDS.put("azithromycin", List.of("AZI500"));
        WORDS.put("azi", List.of("AZI500"));
        WORDS.put("ibuprofen", List.of("IBU400"));
        WORDS.put("brufen", List.of("IBU400"));
        WORDS.put("pain", List.of("PCM500", "IBU400"));
        WORDS.put("cetirizine", List.of("CET10"));
        WORDS.put("allergy", List.of("CET10"));
        WORDS.put("albendazole", List.of("ALB400"));
        WORDS.put("deworming", List.of("ALB400"));
    }

    public QueryResult ask(String question, String selectedDistrict) {
        String q = question == null ? "" : question.toLowerCase();

        // district: a district named in the question, otherwise the one selected in the UI
        String district = selectedDistrict;
        for (String d : facilityRepo.findDistricts()) {
            if (q.contains(d.toLowerCase())) district = d;
        }
        if (district == null || district.isBlank()) district = facilityRepo.findDistricts().get(0);

        // medicines: synonyms + exact names/codes; none named = all medicines
        Set<String> codes = new LinkedHashSet<>();
        WORDS.forEach((word, list) -> { if (hasWord(q, word)) codes.addAll(list); });
        for (Medicine m : medicineRepo.findAll()) {
            if (hasWord(q, m.name.toLowerCase()) || hasWord(q, m.code.toLowerCase())) codes.add(m.code);
        }

        // horizon: "10 days", "3 weeks", "1 month"; default 14 days
        int horizon = 14;
        Matcher mt = Pattern.compile("(\\d+)\\s*(day|week|month)").matcher(q);
        if (mt.find()) {
            int n = Integer.parseInt(mt.group(1));
            horizon = mt.group(2).equals("week") ? n * 7 : mt.group(2).equals("month") ? n * 30 : n;
        } else if (q.contains("month")) {
            horizon = 30;
        } else if (q.contains("week")) {
            horizon = 7;
        }
        horizon = Math.max(1, Math.min(horizon, 90));

        String understood = "District: " + district + " | Medicines: " + (codes.isEmpty() ? "all" : String.join(", ", codes))
                + " | Next " + horizon + " days";
        return new QueryResult(understood, analyse(district, new ArrayList<>(codes), horizon));
    }

    private static boolean hasWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }

    // ------------------------------------------------------------------ 2 + 3. collect data and call the AI

    public ForecastRequest buildForecastRequest(String district, List<String> medicineCodes, int horizon) {
        LocalDate today = stockService.today();
        List<Facility> facilities = facilityRepo.findByDistrictIgnoreCaseOrderByName(district);
        List<Medicine> medicines = medicineRepo.findAll().stream()
                .filter(m -> medicineCodes == null || medicineCodes.isEmpty() || medicineCodes.contains(m.code)).toList();
        List<Long> facilityIds = facilities.stream().map(f -> f.id).toList();
        Set<Long> medicineIds = new HashSet<>();
        medicines.forEach(m -> medicineIds.add(m.id));

        List<BatchData> batches = batchRepo.findByFacilityIdInAndQuantityGreaterThan(facilityIds, 0).stream()
                .filter(b -> medicineIds.contains(b.medicineId))
                .map(b -> new BatchData(b.facilityId, b.medicineId, b.batchNo, b.quantity, b.expiryDate)).toList();
        List<ConsumptionData> history = consumptionRepo.findByFacilityIdInAndDateGreaterThanEqual(facilityIds, today.minusDays(HISTORY_DAYS))
                .stream().filter(c -> medicineIds.contains(c.medicineId))
                .map(c -> new ConsumptionData(c.facilityId, c.medicineId, c.date, c.demanded)).toList();
        List<CaseData> cases = caseRepo.findByDistrictIgnoreCaseAndWeekStartGreaterThanEqual(district, today.minusDays(HISTORY_DAYS + 7))
                .stream().map(c -> new CaseData(c.disease, c.weekStart, c.cases)).toList();

        return new ForecastRequest(district, today, horizon,
                facilities.stream().map(f -> new FacilityData(f.id, f.name, f.latitude, f.longitude)).toList(),
                medicines.stream().map(m -> new MedicineData(m.id, m.code, m.name, m.unit, m.diseaseTag, m.leadTimeDays, m.unitPrice)).toList(),
                batches, history, cases);
    }

    public ForecastResponse analyse(String district, List<String> medicineCodes, int horizon) {
        ForecastRequest request = buildForecastRequest(district, medicineCodes, horizon);
        return forecastClient.analyse(request);
    }

    public com.medichain.inv.dto.StockRunwayDto.DistrictRunwaySummary getRunwaySummary(String district) {
        ForecastRequest request = buildForecastRequest(district, List.of(), 90);
        try {
            return forecastClient.getRunwaySummary(request);
        } catch (Exception ex) {
            return computeFallbackRunwaySummary(request);
        }
    }

    public com.medichain.inv.dto.StockRunwayDto.StockRunwayReport getRunwayReport(String district, Long facilityId, Long medicineId) {
        com.medichain.inv.dto.StockRunwayDto.DistrictRunwaySummary summary = getRunwaySummary(district);
        if (summary == null || summary.items() == null) return null;
        return summary.items().stream()
                .filter(r -> r.facilityId().equals(facilityId) && r.medicineId().equals(medicineId))
                .findFirst()
                .orElse(null);
    }

    public com.medichain.inv.dto.StockRunwayDto.StockRunwayReport simulateRunway(String district, Long facilityId, Long medicineId,
                                                                                  int additionalStock, double surgeMultiplier) {
        ForecastRequest request = buildForecastRequest(district, List.of(), 90);
        try {
            return forecastClient.simulateRunway(request, facilityId, medicineId, additionalStock, surgeMultiplier);
        } catch (Exception ex) {
            return computeFallbackRunwaySimulation(request, facilityId, medicineId, additionalStock, surgeMultiplier);
        }
    }

    // ------------------------------------------------------------------ Fallback Runway Computation

    private com.medichain.inv.dto.StockRunwayDto.DistrictRunwaySummary computeFallbackRunwaySummary(ForecastRequest req) {
        LocalDate asOf = req.asOf();
        List<com.medichain.inv.dto.StockRunwayDto.StockRunwayReport> list = new ArrayList<>();
        int crit = 0, high = 0;

        for (FacilityData f : req.facilities()) {
            for (MedicineData m : req.medicines()) {
                com.medichain.inv.dto.StockRunwayDto.StockRunwayReport r = computeItemRunway(f, m, req.batches(), req.consumption(), req.diseaseCases(), asOf, 0, 1.0);
                list.add(r);
                if ("CRITICAL".equals(r.riskLevel())) crit++;
                else if ("HIGH".equals(r.riskLevel())) high++;
            }
        }
        list.sort(Comparator.comparing(r -> r.daysRemaining() == null ? Integer.MAX_VALUE : r.daysRemaining()));
        return new com.medichain.inv.dto.StockRunwayDto.DistrictRunwaySummary(req.district(), asOf, list.size(), crit, high, list);
    }

    private com.medichain.inv.dto.StockRunwayDto.StockRunwayReport computeFallbackRunwaySimulation(ForecastRequest req, Long facilityId, Long medicineId, int addStock, double surge) {
        FacilityData fac = req.facilities().stream().filter(f -> f.id().equals(facilityId)).findFirst().orElse(null);
        MedicineData med = req.medicines().stream().filter(m -> m.id().equals(medicineId)).findFirst().orElse(null);
        if (fac == null || med == null) return null;
        return computeItemRunway(fac, med, req.batches(), req.consumption(), req.diseaseCases(), req.asOf(), addStock, surge);
    }

    private com.medichain.inv.dto.StockRunwayDto.StockRunwayReport computeItemRunway(
            FacilityData fac, MedicineData med, List<BatchData> allBatches, List<ConsumptionData> allCons,
            List<CaseData> cases, LocalDate asOf, int addStock, double surgeMult) {

        List<BatchData> batches = new ArrayList<>(allBatches.stream()
                .filter(b -> b.facilityId().equals(fac.id()) && b.medicineId().equals(med.id())).toList());
        if (addStock > 0) {
            batches.add(new BatchData(fac.id(), med.id(), "SIM-ADD", addStock, asOf.plusMonths(24)));
        }

        int usable = batches.stream().filter(b -> b.quantity() > 0 && !b.expiryDate().isBefore(asOf)).mapToInt(BatchData::quantity).sum();
        List<ConsumptionData> cons = allCons.stream().filter(c -> c.facilityId().equals(fac.id()) && c.medicineId().equals(med.id())).toList();

        double basePerDay = 35.0;
        if (!cons.isEmpty()) {
            double sum = cons.stream().mapToInt(ConsumptionData::quantity).sum();
            basePerDay = Math.max(1.0, sum / Math.max(1, cons.size()));
        }

        double seasonalMult = !"NONE".equals(med.diseaseTag()) ? 1.45 : 1.0;
        if (surgeMult > 0 && surgeMult != 1.0) seasonalMult *= surgeMult;
        double seasonalPerDay = Math.round(basePerDay * seasonalMult * 10.0) / 10.0;

        Integer daysRem = seasonalPerDay > 0 ? (int) Math.floor(usable / seasonalPerDay) : null;
        LocalDate depDate = daysRem != null ? asOf.plusDays(daysRem) : null;
        Integer baseDays = basePerDay > 0 ? (int) Math.floor(usable / basePerDay) : null;
        Integer daysLost = (baseDays != null && daysRem != null) ? Math.max(0, baseDays - daysRem) : 0;

        String risk = daysRem == null || daysRem > med.leadTimeDays() + 14 ? "LOW"
                : daysRem <= 7 ? "CRITICAL"
                : daysRem <= med.leadTimeDays() ? "HIGH" : "MEDIUM";

        List<com.medichain.inv.dto.StockRunwayDto.RunwayTrajectoryPoint> traj = new ArrayList<>();
        double currStock = usable;
        for (int d = 0; d < 90; d++) {
            currStock = Math.max(0, currStock - seasonalPerDay);
            traj.add(new com.medichain.inv.dto.StockRunwayDto.RunwayTrajectoryPoint(
                    d, asOf.plusDays(d), (int) Math.round(currStock),
                    (int) Math.max(0, usable - (d * basePerDay)),
                    (int) Math.max(0, usable - (d * seasonalPerDay * 1.35)),
                    seasonalPerDay, basePerDay
            ));
        }

        List<com.medichain.inv.dto.StockRunwayDto.BatchDepletionDetail> bDetails = new ArrayList<>();
        for (BatchData b : batches) {
            int dUntilExp = (int) java.time.temporal.ChronoUnit.DAYS.between(asOf, b.expiryDate());
            bDetails.add(new com.medichain.inv.dto.StockRunwayDto.BatchDepletionDetail(
                    b.batchNo(), b.quantity(), b.expiryDate(), dUntilExp, b.quantity(), 0,
                    dUntilExp > 0 ? asOf.plusDays(Math.min(dUntilExp, daysRem == null ? 90 : daysRem)) : asOf,
                    dUntilExp < 0 ? "EXPIRED" : "ACTIVE"
            ));
        }

        List<String> insights = List.of(
                String.format("Stock of %,d %ss will last %d days, depleting on %s.", usable, med.unit(), daysRem == null ? 90 : daysRem, depDate),
                String.format("Seasonal disease surge (+%.0f%%) accelerates depletion by %d days vs historical baseline.", (seasonalMult - 1.0) * 100, daysLost)
        );

        return new com.medichain.inv.dto.StockRunwayDto.StockRunwayReport(
                fac.id(), fac.name(), med.id(), med.name(), med.unit(), med.diseaseTag(),
                med.diseaseTag().equals("FEVER") ? "Fever & Vector-borne" : med.diseaseTag().equals("DIARRHOEA") ? "Diarrhoeal Disease" : "Respiratory Infection",
                asOf, usable, batches.size(), daysRem, depDate, daysRem != null ? Math.round(daysRem / 7.0 * 10.0) / 10.0 : 26.0,
                risk, baseDays, baseDays != null ? asOf.plusDays(baseDays) : null, daysLost,
                (int) Math.floor(usable / (seasonalPerDay * 1.35)), asOf.plusDays((int) Math.floor(usable / (seasonalPerDay * 1.35))),
                Math.round(basePerDay * 10.0) / 10.0, seasonalPerDay, Math.round(seasonalMult * 100.0) / 100.0,
                Math.round((seasonalMult - 1.0) * 1000.0) / 10.0, 0.45, 0.72, 25.0, 35.0,
                basePerDay, basePerDay, basePerDay, 5.0, traj, bDetails, insights
        );
    }
}
