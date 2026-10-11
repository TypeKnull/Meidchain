package com.medichain.inv.service;

import com.medichain.inv.entity.Facility;
import com.medichain.inv.entity.Medicine;
import com.medichain.inv.repository.FacilityRepository;
import com.medichain.inv.repository.MedicineRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Master data: facilities and the essential medicine list. */
@Service
public class MasterService {

    private final FacilityRepository facilityRepo;
    private final MedicineRepository medicineRepo;

    public MasterService(FacilityRepository facilityRepo, MedicineRepository medicineRepo) {
        this.facilityRepo = facilityRepo;
        this.medicineRepo = medicineRepo;
    }

    public List<String> districts() {
        return facilityRepo.findDistricts();
    }

    public List<Facility> facilities(String district) {
        return district == null || district.isBlank() ? facilityRepo.findAll()
                : facilityRepo.findByDistrictIgnoreCaseOrderByName(district);
    }

    public Facility addFacility(Facility f) {
        f.id = null;
        return facilityRepo.save(f);
    }

    public List<Medicine> medicines() {
        return medicineRepo.findAll();
    }

    public Medicine addMedicine(Medicine m) {
        m.id = null;
        if (m.diseaseTag == null || m.diseaseTag.isBlank()) m.diseaseTag = "NONE";
        return medicineRepo.save(m);
    }
}
