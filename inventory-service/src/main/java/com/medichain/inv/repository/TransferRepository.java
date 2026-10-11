package com.medichain.inv.repository;

import com.medichain.inv.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, Long> {
    List<Transfer> findAllByOrderByIdDesc();
    List<Transfer> findByToFacilityIdAndMedicineIdAndStatusIn(Long toFacilityId, Long medicineId, List<String> statuses);
}
