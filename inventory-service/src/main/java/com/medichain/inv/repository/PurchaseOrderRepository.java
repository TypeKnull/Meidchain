package com.medichain.inv.repository;

import com.medichain.inv.entity.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
    List<PurchaseOrder> findAllByOrderByIdDesc();
    List<PurchaseOrder> findByFacilityId(Long facilityId);
    List<PurchaseOrder> findByStatus(String status);
    List<PurchaseOrder> findByFacilityIdAndMedicineIdAndStatusIn(Long facilityId, Long medicineId, List<String> statuses);
}
