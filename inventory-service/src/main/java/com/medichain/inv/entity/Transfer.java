package com.medichain.inv.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A stock transfer between facilities. Status: APPROVED -> COMPLETED (stock moved) or REJECTED. */
@Entity
public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long fromFacilityId;
    public Long toFacilityId;
    public Long medicineId;
    public int quantity;
    public double distanceKm;
    public String status;
    @Column(length = 2000)
    public String rationale;
    public LocalDateTime createdAt;
}
