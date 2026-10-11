package com.medichain.inv.repository;

import com.medichain.inv.entity.StockBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StockBatchRepository extends JpaRepository<StockBatch, Long> {
    /** Batches of one medicine at one facility, earliest expiry first (FEFO). */
    List<StockBatch> findByFacilityIdAndMedicineIdAndQuantityGreaterThanOrderByExpiryDateAsc(Long facilityId, Long medicineId, int min);

    List<StockBatch> findByFacilityIdInAndQuantityGreaterThan(Collection<Long> facilityIds, int min);
}
