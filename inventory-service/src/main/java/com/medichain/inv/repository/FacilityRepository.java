package com.medichain.inv.repository;

import com.medichain.inv.entity.Facility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FacilityRepository extends JpaRepository<Facility, Long> {
    List<Facility> findByDistrictIgnoreCaseOrderByName(String district);

    /** Districts, the one with most facilities first. */
    @Query("select f.district from Facility f group by f.district order by count(f) desc")
    List<String> findDistricts();
}
