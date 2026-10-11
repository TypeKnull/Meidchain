package com.medichain.inv.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A purchase order for procuring medicines from external suppliers/depots.
 * Created when an operator approves an AI procurement suggestion.
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long facilityId;
    public Long medicineId;
    public int quantity;
    public int leadTimeDays;
    public LocalDate expectedDeliveryDate;
    public double unitPrice;
    public double totalCost;
    public String urgency; // URGENT, ROUTINE, PLANNED
    public String status;  // ORDERED, RECEIVED, CANCELLED

    public LocalDateTime createdAt;
    public LocalDateTime receivedAt;
}
