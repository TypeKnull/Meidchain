package com.medichain.inv.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/** One batch (lot) of a medicine at a facility. Stock of a medicine = sum of its batches. */
@Entity
public class StockBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long facilityId;
    public Long medicineId;
    public String batchNo;
    public int quantity;
    public LocalDate expiryDate;
}
