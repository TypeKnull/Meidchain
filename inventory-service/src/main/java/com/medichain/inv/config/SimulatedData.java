package com.medichain.inv.config;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * Generates realistic, repeatable demo data (no Spring, no database - plain Java).
 *
 * - Facilities: real town names in Vellore and Ranipet districts (Tamil Nadu), with approximate coordinates.
 * - Medicines: names/strengths from the WHO Model List of Essential Medicines and India's NLEM 2022.
 * - Disease cases: weekly FEVER / DIARRHOEA / ARI counts following a monsoon-season curve
 *   (North-East monsoon, Oct-Dec in Tamil Nadu).
 * - Consumption: driven by facility size, the disease curve, weekday effect and random noise.
 * - Stock: batches sized to create a mix of shortage, surplus and near-expiry situations,
 *   plus a few already-expired batches (wastage).
 *
 * All numbers are SIMULATED. Seed is fixed so every run produces the same data.
 */
public class SimulatedData {

    public record SimFacility(String code, String name, String type, String district, double lat, double lon, int population) { }
    public record SimMedicine(String code, String name, String strength, String form, String unit, String category,
                              String disease, int leadTimeDays, double unitPrice, String source) { }
    public record SimBatch(String facilityCode, String medicineCode, String batchNo, int quantity,
                           LocalDate expiryDate, LocalDate receivedDate) { }
    public record SimConsumption(String facilityCode, String medicineCode, LocalDate date, int issued, int demanded) { }
    public record SimCases(String district, String disease, LocalDate weekStart, int cases) { }

    public record SimData(List<SimFacility> facilities, List<SimMedicine> medicines, List<SimBatch> batches,
                          List<SimConsumption> consumption, List<SimCases> cases) { }

    private static final String SOURCE = "WHO EML 2023 / India NLEM 2022";

    public static List<SimFacility> facilities() {
        return List.of(
            new SimFacility("DH-VLR", "Vellore District Hospital", "DH", "Vellore", 12.9165, 79.1325, 90000),
            new SimFacility("CHC-KPD", "CHC Katpadi", "CHC", "Vellore", 12.9692, 79.1459, 55000),
            new SimFacility("PHC-GDY", "PHC Gudiyatham", "PHC", "Vellore", 12.9446, 78.8731, 42000),
            new SimFacility("PHC-ANC", "PHC Anaicut", "PHC", "Vellore", 12.9930, 79.0070, 30000),
            new SimFacility("PHC-KNB", "PHC Kaniyambadi", "PHC", "Vellore", 12.8456, 79.1338, 28000),
            new SimFacility("PHC-PNB", "PHC Pernambut", "PHC", "Vellore", 12.9393, 78.7178, 32000),
            new SimFacility("PHC-PLK", "PHC Pallikonda", "PHC", "Vellore", 12.9067, 78.9411, 30000),
            new SimFacility("PHC-PNI", "PHC Ponnai", "PHC", "Vellore", 13.1247, 79.2533, 24000),
            new SimFacility("DH-WLJ", "Walajapet District Hospital", "DH", "Ranipet", 12.9256, 79.3667, 80000),
            new SimFacility("PHC-ARC", "PHC Arcot", "PHC", "Ranipet", 12.9067, 79.3197, 34000),
            new SimFacility("PHC-SHL", "PHC Sholinghur", "PHC", "Ranipet", 13.1156, 79.4233, 30000)
        );
    }

    public static List<SimMedicine> medicines() {
        return List.of(
            new SimMedicine("PCM500", "Paracetamol", "500 mg", "tablet", "tablet", "Analgesic / antipyretic", "FEVER", 14, 0.25, SOURCE),
            new SimMedicine("ORS", "Oral Rehydration Salts", "20.5 g/L (WHO formula)", "powder sachet", "sachet", "Rehydration", "DIARRHOEA", 10, 3.00, SOURCE),
            new SimMedicine("ZINC20", "Zinc sulphate", "20 mg", "dispersible tablet", "tablet", "Diarrhoea adjunct", "DIARRHOEA", 14, 0.80, SOURCE),
            new SimMedicine("AMOX500", "Amoxicillin", "500 mg", "capsule", "capsule", "Antibiotic", "ARI", 21, 1.50, SOURCE),
            new SimMedicine("AZI500", "Azithromycin", "500 mg", "tablet", "tablet", "Macrolide Antibiotic", "ARI", 14, 12.00, SOURCE),
            new SimMedicine("MET500", "Metformin", "500 mg", "tablet", "tablet", "Antidiabetic", "NONE", 21, 0.40, SOURCE),
            new SimMedicine("IFA", "Ferrous sulphate + Folic acid", "100 mg + 0.5 mg", "tablet", "tablet", "Anaemia", "NONE", 21, 0.20, SOURCE),
            new SimMedicine("AMLO5", "Amlodipine", "5 mg", "tablet", "tablet", "Antihypertensive", "NONE", 21, 0.35, SOURCE),
            new SimMedicine("IBU400", "Ibuprofen", "400 mg", "tablet", "tablet", "Analgesic / Anti-inflammatory", "FEVER", 14, 0.45, SOURCE),
            new SimMedicine("CET10", "Cetirizine", "10 mg", "tablet", "tablet", "Antihistamine / Allergy", "NONE", 14, 0.30, SOURCE),
            new SimMedicine("ALB400", "Albendazole", "400 mg", "chewable tablet", "tablet", "Anthelminthic", "NONE", 21, 1.20, SOURCE)
        );
    }

    /** Base daily demand at a standard 30k-population PHC outside the disease season. */
    private static final Map<String, Double> BASE_DEMAND = Map.ofEntries(
            Map.entry("PCM500", 40.0), Map.entry("ORS", 14.0), Map.entry("ZINC20", 10.0),
            Map.entry("AMOX500", 18.0), Map.entry("AZI500", 12.0), Map.entry("MET500", 30.0),
            Map.entry("IFA", 25.0), Map.entry("AMLO5", 15.0), Map.entry("IBU400", 25.0),
            Map.entry("CET10", 20.0), Map.entry("ALB400", 10.0));

    /** Weekly district cases outside season (Vellore). Ranipet is scaled by 0.6. */
    private static final Map<String, Double> BASE_CASES = Map.of("FEVER", 900.0, "DIARRHOEA", 600.0, "ARI", 700.0);

    /**
     * Seasonal multiplier for a disease on a date (1.0 = off-season).
     * Gaussian bump around the typical peak day-of-year for Tamil Nadu.
     */
    public static double season(String disease, LocalDate date) {
        int doy = date.getDayOfYear();
        return switch (disease) {
            case "FEVER" -> 1.0 + 1.3 * Math.exp(-Math.pow((doy - 300) / 32.0, 2));      // peak ~27 Oct (dengue/viral fever)
            case "DIARRHOEA" -> 1.0 + 1.1 * Math.exp(-Math.pow((doy - 292) / 38.0, 2));  // peak ~19 Oct (monsoon water contamination)
            case "ARI" -> 1.0 + 0.9 * Math.exp(-Math.pow((doy - 330) / 45.0, 2));        // peak ~26 Nov (cold/wet season)
            default -> 1.0;
        };
    }

    public static SimData generate(LocalDate asOf, int historyDays) {
        Random rnd = new Random(2026);
        List<SimFacility> facilities = facilities();
        List<SimMedicine> medicines = medicines();
        LocalDate start = asOf.minusDays(historyDays);

        // ---- 1. Weekly disease cases per district ----
        List<SimCases> cases = new ArrayList<>();
        LocalDate week = start.with(DayOfWeek.MONDAY);
        while (!week.isAfter(asOf)) {
            for (String district : List.of("Vellore", "Ranipet")) {
                double scale = district.equals("Vellore") ? 1.0 : 0.6;
                for (String disease : List.of("FEVER", "DIARRHOEA", "ARI")) {
                    double mid = season(disease, week.plusDays(3));
                    int c = (int) Math.round(BASE_CASES.get(disease) * scale * mid * (0.92 + 0.16 * rnd.nextDouble()));
                    cases.add(new SimCases(district, disease, week, c));
                }
            }
            week = week.plusWeeks(1);
        }

        // ---- 2. Daily consumption per facility x medicine ----
        List<SimConsumption> consumption = new ArrayList<>();
        Map<String, Double> recentAvg = new HashMap<>();   // avg demand over last 14 days, used to size stock
        for (SimFacility f : facilities) {
            double facScale = switch (f.type()) {
                case "DH" -> 3.0;
                case "CHC" -> 1.6;
                default -> 1.0;
            } * (f.population() / 30000.0) * (f.type().equals("PHC") ? 1.0 : 0.5);
            for (SimMedicine m : medicines) {
                double sum14 = 0;
                for (int i = historyDays; i >= 1; i--) {
                    LocalDate d = asOf.minusDays(i);
                    double seasonal = m.disease().equals("NONE") ? 1.0 : 0.4 + 0.6 * season(m.disease(), d);
                    double weekday = switch (d.getDayOfWeek()) {
                        case SUNDAY -> 0.35;
                        case SATURDAY -> 0.8;
                        default -> 1.05;
                    };
                    double localOutbreak = 1.0;
                    // A local fever cluster around Gudiyatham in the last 3 weeks
                    if (f.code().equals("PHC-GDY") && m.code().equals("PCM500") && i <= 21) {
                        localOutbreak = 1.0 + 0.45 * (21 - i) / 21.0;
                    }
                    double expected = BASE_DEMAND.get(m.code()) * facScale * seasonal * weekday * localOutbreak;
                    int demanded = Math.max(0, (int) Math.round(expected * (0.85 + 0.3 * rnd.nextDouble())));
                    int issued = demanded;
                    // Historical stock-out episodes (for the dashboard stock-out / fill-rate metrics)
                    if (f.code().equals("PHC-GDY") && m.code().equals("PCM500") && i >= 34 && i <= 40) issued = (int) (demanded * 0.3);
                    if (f.code().equals("PHC-PNB") && m.code().equals("AMOX500") && i >= 63 && i <= 70) issued = 0;
                    if (f.code().equals("PHC-PNI") && m.code().equals("ORS") && i >= 22 && i <= 25) issued = demanded / 2;
                    if (f.code().equals("PHC-SHL") && m.code().equals("PCM500") && i >= 12 && i <= 14) issued = (int) (demanded * 0.4);
                    consumption.add(new SimConsumption(f.code(), m.code(), d, issued, demanded));
                    if (i <= 14) sum14 += demanded;
                }
                recentAvg.put(f.code() + "|" + m.code(), sum14 / 14.0);
            }
        }

        // ---- 3. Current stock batches ----
        // Scenario table: days of cover (on recent demand) and an optional near-expiry batch.
        Map<String, double[]> scenario = new HashMap<>(); // {coverDays, nearExpiryShareOfStock, nearExpiryDays}
        scenario.put("PHC-GDY|PCM500", new double[]{6, 0, 0});
        scenario.put("PHC-GDY|ORS", new double[]{5, 0, 0});
        scenario.put("CHC-KPD|PCM500", new double[]{9, 0, 0});
        scenario.put("CHC-KPD|ORS", new double[]{18, 0, 0});
        scenario.put("PHC-PNB|ORS", new double[]{8, 0, 0});
        scenario.put("PHC-PNB|PCM500", new double[]{16, 0, 0});
        scenario.put("PHC-ANC|ZINC20", new double[]{7, 0, 0});
        scenario.put("PHC-ANC|MET500", new double[]{12, 0, 0});
        scenario.put("PHC-KNB|ORS", new double[]{120, 0.55, 40});       // surplus + expiry risk -> ideal donor
        scenario.put("PHC-KNB|PCM500", new double[]{70, 0, 0});
        scenario.put("PHC-PLK|PCM500", new double[]{110, 0.30, 55});    // surplus
        scenario.put("PHC-PLK|ORS", new double[]{30, 0, 0});
        scenario.put("DH-VLR|PCM500", new double[]{60, 0, 0});
        scenario.put("DH-VLR|ORS", new double[]{65, 0, 0});
        scenario.put("PHC-PNI|AMOX500", new double[]{95, 0.6, 30});     // expiry risk
        scenario.put("PHC-PNI|ZINC20", new double[]{80, 0.5, 35});
        scenario.put("DH-WLJ|PCM500", new double[]{95, 0, 0});           // Ranipet surplus
        scenario.put("PHC-SHL|PCM500", new double[]{8, 0, 0});           // Ranipet shortage
        scenario.put("PHC-ARC|ORS", new double[]{11, 0, 0});

        List<SimBatch> batches = new ArrayList<>();
        int batchSeq = 1000;
        for (SimFacility f : facilities) {
            for (SimMedicine m : medicines) {
                String key = f.code() + "|" + m.code();
                double avg = recentAvg.get(key);
                double[] sc = scenario.getOrDefault(key, new double[]{35 + rnd.nextInt(40), 0, 0});
                int total = (int) Math.round(avg * sc[0]);
                int nearQty = (int) Math.round(total * sc[1]);
                if (nearQty > 0) {
                    batches.add(new SimBatch(f.code(), m.code(), "B" + (batchSeq++), nearQty,
                            asOf.plusDays((long) sc[2]), asOf.minusDays(300)));
                }
                int rest = total - nearQty;
                if (rest > 0) {
                    // split remaining stock into one or two batches with long shelf life
                    int first = rest > 200 && rnd.nextBoolean() ? rest / 2 : rest;
                    batches.add(new SimBatch(f.code(), m.code(), "B" + (batchSeq++), first,
                            asOf.plusDays(150 + rnd.nextInt(300)), asOf.minusDays(20 + rnd.nextInt(60))));
                    if (rest - first > 0) {
                        batches.add(new SimBatch(f.code(), m.code(), "B" + (batchSeq++), rest - first,
                                asOf.plusDays(300 + rnd.nextInt(300)), asOf.minusDays(5 + rnd.nextInt(20))));
                    }
                }
            }
        }

        // ---- 4. Expired batches still on the shelf (= wastage, shown on the dashboard)
        batches.add(new SimBatch("PHC-KNB", "ORS", "B0871", 420, asOf.minusDays(62), asOf.minusDays(400)));
        batches.add(new SimBatch("PHC-PNI", "AMOX500", "B0812", 650, asOf.minusDays(45), asOf.minusDays(400)));
        batches.add(new SimBatch("DH-VLR", "PCM500", "B0790", 1200, asOf.minusDays(80), asOf.minusDays(500)));
        batches.add(new SimBatch("PHC-PLK", "ZINC20", "B0833", 180, asOf.minusDays(20), asOf.minusDays(400)));
        batches.add(new SimBatch("DH-WLJ", "IFA", "B0850", 900, asOf.minusDays(33), asOf.minusDays(450)));

        return new SimData(facilities, medicines, batches, consumption, cases);
    }
}
