package com.medichain.inv.controller;

import com.medichain.inv.entity.*;
import com.medichain.inv.repository.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoints for ingesting and importing data from external sources,
 * public health datasets, and external registry files into the MySQL database.
 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final MedicineRepository medicineRepo;
    private final FacilityRepository facilityRepo;
    private final DiseaseCaseRepository diseaseCaseRepo;
    private final StockBatchRepository stockBatchRepo;

    public ImportController(MedicineRepository medicineRepo, FacilityRepository facilityRepo,
                            DiseaseCaseRepository diseaseCaseRepo, StockBatchRepository stockBatchRepo) {
        this.medicineRepo = medicineRepo;
        this.facilityRepo = facilityRepo;
        this.diseaseCaseRepo = diseaseCaseRepo;
        this.stockBatchRepo = stockBatchRepo;
    }

    /** Ingest an external list of essential medicines (e.g. WHO EML or India NLEM exports). */
    @PostMapping("/medicines")
    public Map<String, Object> importMedicines(@RequestBody List<Medicine> medicines) {
        List<Medicine> saved = medicineRepo.saveAll(medicines);
        return Map.of("status", "SUCCESS", "importedCount", saved.size(), "source", "External Medicine Dataset");
    }

    /** Ingest an external facility registry (e.g. state health ministry PHC directory). */
    @PostMapping("/facilities")
    public Map<String, Object> importFacilities(@RequestBody List<Facility> facilities) {
        List<Facility> saved = facilityRepo.saveAll(facilities);
        return Map.of("status", "SUCCESS", "importedCount", saved.size(), "source", "External Facility Directory");
    }

    /** Ingest external epidemiological case surveillance records. */
    @PostMapping("/disease-cases")
    public Map<String, Object> importDiseaseCases(@RequestBody List<DiseaseCase> cases) {
        List<DiseaseCase> saved = diseaseCaseRepo.saveAll(cases);
        return Map.of("status", "SUCCESS", "importedCount", saved.size(), "source", "External Disease Surveillance Dataset");
    }

    /** Ingest external stock lots and batch delivery manifests. */
    @PostMapping("/stock-batches")
    public Map<String, Object> importStockBatches(@RequestBody List<StockBatch> batches) {
        List<StockBatch> saved = stockBatchRepo.saveAll(batches);
        return Map.of("status", "SUCCESS", "importedCount", saved.size(), "source", "External Stock Manifest");
    }
}
