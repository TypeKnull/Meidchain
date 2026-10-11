package com.medichain.inv.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medichain.inv.entity.*;
import com.medichain.inv.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On initial startup with an empty database, loads public datasets from external files
 * (WHO EML and Tamil Nadu facility registry in /datasets/ or classpath) and seeds the MySQL database.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final FacilityRepository facilities;
    private final MedicineRepository medicines;
    private final StockBatchRepository batches;
    private final ConsumptionRepository consumption;
    private final DiseaseCaseRepository cases;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataSeeder(FacilityRepository facilities, MedicineRepository medicines, StockBatchRepository batches,
                      ConsumptionRepository consumption, DiseaseCaseRepository cases) {
        this.facilities = facilities;
        this.medicines = medicines;
        this.batches = batches;
        this.consumption = consumption;
        this.cases = cases;
    }

    @Override
    public void run(String... args) {
        if (facilities.count() > 0) return;   // already seeded in MySQL

        SimulatedData.SimData data = SimulatedData.generate(LocalDate.now(), 120);
        Map<String, Long> facilityIds = new HashMap<>();

        // 1. Facilities: attempt loading from external JSON dataset first
        List<Map<String, Object>> externalFacilities = loadExternalJson("datasets/public_health_facilities.json");
        if (externalFacilities != null && !externalFacilities.isEmpty()) {
            for (var m : externalFacilities) {
                Facility f = new Facility();
                f.code = (String) m.get("code");
                f.name = (String) m.get("name");
                f.type = (String) m.get("type");
                f.district = (String) m.get("district");
                f.latitude = ((Number) m.get("lat")).doubleValue();
                f.longitude = ((Number) m.get("lon")).doubleValue();
                f.population = ((Number) m.get("population")).intValue();
                facilityIds.put(f.code, facilities.save(f).id);
            }
            System.out.println("MediChain: Loaded " + facilityIds.size() + " facilities from external public dataset.");
        } else {
            for (var s : data.facilities()) {
                Facility f = new Facility();
                f.code = s.code();
                f.name = s.name();
                f.type = s.type();
                f.district = s.district();
                f.latitude = s.lat();
                f.longitude = s.lon();
                f.population = s.population();
                facilityIds.put(s.code(), facilities.save(f).id);
            }
        }

        // 2. Essential Medicines: attempt loading from external public EML dataset first
        Map<String, Long> medicineIds = new HashMap<>();
        List<Map<String, Object>> externalMedicines = loadExternalJson("datasets/public_essential_medicines.json");
        if (externalMedicines != null && !externalMedicines.isEmpty()) {
            for (var m : externalMedicines) {
                Medicine med = new Medicine();
                med.code = (String) m.get("code");
                med.name = (String) m.get("name");
                med.strength = (String) m.get("strength");
                med.unit = (String) m.get("unit");
                med.diseaseTag = (String) m.get("disease");
                med.leadTimeDays = ((Number) m.get("leadTimeDays")).intValue();
                med.unitPrice = ((Number) m.get("unitPrice")).doubleValue();
                med.source = (String) m.get("source");
                medicineIds.put(med.code, medicines.save(med).id);
            }
            System.out.println("MediChain: Loaded " + medicineIds.size() + " essential medicines from external WHO EML dataset.");
        } else {
            for (var s : data.medicines()) {
                Medicine m = new Medicine();
                m.code = s.code();
                m.name = s.name();
                m.strength = s.strength();
                m.unit = s.unit();
                m.diseaseTag = s.disease();
                m.leadTimeDays = s.leadTimeDays();
                m.unitPrice = s.unitPrice();
                m.source = s.source();
                medicineIds.put(s.code(), medicines.save(m).id);
            }
        }

        batches.saveAll(data.batches().stream().map(s -> {
            StockBatch b = new StockBatch();
            b.facilityId = facilityIds.get(s.facilityCode());
            b.medicineId = medicineIds.get(s.medicineCode());
            b.batchNo = s.batchNo();
            b.quantity = s.quantity();
            b.expiryDate = s.expiryDate();
            return b;
        }).toList());

        consumption.saveAll(data.consumption().stream().map(s -> {
            Consumption c = new Consumption();
            c.facilityId = facilityIds.get(s.facilityCode());
            c.medicineId = medicineIds.get(s.medicineCode());
            c.date = s.date();
            c.issued = s.issued();
            c.demanded = s.demanded();
            return c;
        }).toList());

        cases.saveAll(data.cases().stream().map(s -> {
            DiseaseCase d = new DiseaseCase();
            d.district = s.district();
            d.disease = s.disease();
            d.weekStart = s.weekStart();
            d.cases = s.cases();
            return d;
        }).toList());

        System.out.println("MediChain: Initial database seed complete (" + data.consumption().size() + " consumption rows).");
    }

    private List<Map<String, Object>> loadExternalJson(String path) {
        try {
            // Check external file system first
            File file = new File(path);
            if (file.exists()) {
                return objectMapper.readValue(file, new TypeReference<>() {});
            }
            // Check classpath resource
            InputStream is = getClass().getClassLoader().getResourceAsStream(path);
            if (is != null) {
                return objectMapper.readValue(is, new TypeReference<>() {});
            }
        } catch (Exception e) {
            System.err.println("Note: Could not read external file " + path + ": " + e.getMessage() + ", falling back to internal catalog.");
        }
        return null;
    }
}
