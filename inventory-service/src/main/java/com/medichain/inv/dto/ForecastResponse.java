package com.medichain.inv.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Result of the AI analysis. (Identical copy of the class in the Forecast Service - this is the REST contract.)
 */
public record ForecastResponse(
        String district,
        LocalDate asOf,
        int horizonDays,
        List<String> summary,           // plain-English answer
        List<RiskRow> risks,            // step 1: stock-out risk
        List<TransferRow> transfers,    // step 2: redistribution
        List<OrderRow> orders,          // step 3: procurement
        List<ExpiryRow> expiry,         // step 4: expiry risk
        String method) {                // how it was calculated

    /** Stock-out risk of one medicine at one facility. */
    public record RiskRow(Long facilityId, String facilityName, Long medicineId, String medicineName, String unit,
                          int usableStock, double usedPerDayLast14, double forecastPerDay,
                          Integer daysToStockout,      // null = more than 180 days
                          LocalDate stockoutDate,
                          double stockoutChancePct,    // chance of running out within the horizon
                          String riskLevel,            // CRITICAL / HIGH / MEDIUM / LOW
                          boolean runsOutInHorizon,
                          List<String> keyFactors,     // the explanation
                          String model,
                          List<Double> forecastNext30) { }

    /** A recommended transfer from a surplus facility to a deficit facility. */
    public record TransferRow(Long fromFacilityId, String fromFacility, Long toFacilityId, String toFacility,
                              Long medicineId, String medicineName, String unit, int quantity, double distanceKm,
                              Integer daysOfStockBefore, Integer daysOfStockAfter, String urgency,
                              int expiringUnitsRescued, String rationale) { }

    /** A purchase order suggestion. */
    public record OrderRow(Long facilityId, String facilityName, Long medicineId, String medicineName,
                           int stockAfterTransfers, double forecastPerDay, int leadTimeDays, int safetyStock,
                           int reorderPoint, int orderQuantity, LocalDate orderBy, String urgency,
                           double costInr, String reason) { }

    /** A batch that will probably expire before it is used. */
    public record ExpiryRow(Long facilityId, String facilityName, Long medicineId, String medicineName,
                            String batchNo, LocalDate expiryDate, int daysToExpiry, int batchQuantity,
                            int unitsAtRisk, double valueInr, String action) { }
}
