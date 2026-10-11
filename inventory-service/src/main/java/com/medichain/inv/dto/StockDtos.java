package com.medichain.inv.dto;

import java.time.LocalDate;

/** Small request/response objects used by the Inventory REST API. */
public final class StockDtos {
    private StockDtos() { }

    /** Receive a new batch of stock. */
    public record NewBatch(Long facilityId, Long medicineId, String batchNo, int quantity, LocalDate expiryDate) { }

    /** Record a day's consumption. If demanded is empty it is taken as equal to issued. */
    public record ConsumptionEntry(Long facilityId, Long medicineId, LocalDate date, int issued, Integer demanded) { }

    /** Operator approves a recommended transfer. */
    public record TransferApproval(Long fromFacilityId, Long toFacilityId, Long medicineId, int quantity,
                                   double distanceKm, String rationale) { }

    /** One line of the stock table: a medicine at a facility. */
    public record StockRow(String facility, String medicine, int usableQuantity, int batches,
                           LocalDate nearestExpiry, int expiredQuantity) { }

    /** A transfer with names filled in for the UI. */
    public record TransferView(Long id, String fromFacility, String toFacility, String medicine, int quantity,
                               double distanceKm, String status, String rationale, String createdAt) { }

    /** One day of history for the chart. */
    public record DayUsage(LocalDate date, int demanded, int issued) { }

    /** Create a new purchase order from AI suggestions. */
    public record CreatePurchaseOrder(Long facilityId, Long medicineId, int quantity, Integer leadTimeDays,
                                     LocalDate expectedDeliveryDate, Double unitPrice, Double totalCost, String urgency) { }

    /** Purchase order details formatted for the UI. */
    public record PurchaseOrderView(Long id, Long facilityId, Long medicineId, String facility, String medicine,
                                    int quantity, int leadTimeDays, LocalDate expectedDeliveryDate, double totalCost,
                                    String urgency, String status, String createdAt, String receivedAt) { }

    /** Receive stock for an approved purchase order. */
    public record ReceivePurchaseOrder(String batchNo, LocalDate expiryDate) { }

    /** Detailed view of an individual batch for inventory auditing. */
    public record BatchDetailRow(Long id, Long facilityId, Long medicineId, String facility, String medicine,
                                 String batchNo, int quantity, LocalDate expiryDate, long daysToExpiry, boolean isExpired) { }
}
