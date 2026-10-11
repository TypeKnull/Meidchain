package com.medichain.inv.repository;

import com.medichain.inv.entity.DiseaseCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DiseaseCaseRepository extends JpaRepository<DiseaseCase, Long> {
    List<DiseaseCase> findByDistrictIgnoreCaseAndWeekStartGreaterThanEqual(String district, LocalDate from);
    List<DiseaseCase> findByDistrictIgnoreCaseOrderByWeekStartAsc(String district);
}
