package com.medichain.inv.repository;

import com.medichain.inv.entity.Consumption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConsumptionRepository extends JpaRepository<Consumption, Long> {
    List<Consumption> findByFacilityIdInAndDateGreaterThanEqual(Collection<Long> facilityIds, LocalDate from);

    List<Consumption> findByFacilityIdAndMedicineIdAndDateGreaterThanEqualOrderByDateAsc(Long facilityId, Long medicineId, LocalDate from);

    Optional<Consumption> findByFacilityIdAndMedicineIdAndDate(Long facilityId, Long medicineId, LocalDate date);

    /** Latest recorded day - used as "today" for the analysis. */
    Optional<Consumption> findTopByOrderByDateDesc();
}
