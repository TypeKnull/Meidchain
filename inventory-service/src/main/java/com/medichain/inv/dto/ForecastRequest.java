package com.medichain.inv.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the AI needs, sent by the Inventory Service in one call.
 * (Identical copy of the class in the Forecast Service - this is the REST contract.)
 */
public record ForecastRequest(
        String district,
        LocalDate asOf,                 // "today" - first day to forecast
        int horizonDays,                // e.g. 10 for "next 10 days"
        List<FacilityData> facilities,
        List<MedicineData> medicines,
        List<BatchData> batches,        // current stock, per batch
        List<ConsumptionData> consumption, // daily history
        List<CaseData> diseaseCases) {  // weekly disease cases in the district

    public record FacilityData(Long id, String name, double latitude, double longitude) { }

    public record MedicineData(Long id, String code, String name, String unit,
                               String diseaseTag, int leadTimeDays, double unitPrice) { }

    public record BatchData(Long facilityId, Long medicineId, String batchNo, int quantity, LocalDate expiryDate) { }

    /** quantity = units DEMANDED that day (not only issued), so past stock-outs don't hide real demand. */
    public record ConsumptionData(Long facilityId, Long medicineId, LocalDate date, int quantity) { }

    public record CaseData(String disease, LocalDate weekStart, int cases) { }
}
