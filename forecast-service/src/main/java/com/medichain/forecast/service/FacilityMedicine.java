package com.medichain.forecast.service;

import com.medichain.forecast.dto.ForecastRequest.*;
import com.medichain.forecast.ml.DemandPredictionModel;

import java.time.LocalDate;
import java.util.*;

/**
 * Working data for ONE medicine at ONE facility while the analysis runs:
 * its history, its forecast, its stock simulation, and transfer bookkeeping.
 */
public class FacilityMedicine {

    public FacilityData facility;
    public MedicineData medicine;

    // inputs
    public LocalDate historyStart;
    public double[] demandHistory;            // one value per day
    public List<BatchData> batches = new ArrayList<>();

    // AI results
    public DemandPredictionModel.Prediction prediction;
    public Double backtestErrorPct;
    public Integer daysToStockout;            // null = lasts the whole simulation
    public int usableStock;                   // non-expired units today
    public Map<String, Integer> unitsExpiring = new LinkedHashMap<>(); // batchNo -> units that expire unused

    // filled in by RedistributionService
    public int surplus;                       // units this facility can safely give away
    public int incoming;
    public int outgoing;
    public int expiringRescued;               // expiring units sent to other facilities
    public Map<String, Integer> movableLeft = new LinkedHashMap<>(); // batchNo -> units still available to send
    public Map<String, Integer> expiringLeft = new HashMap<>();      // batchNo -> expiring units not yet rescued

    public int totalExpiring() {
        return unitsExpiring.values().stream().mapToInt(Integer::intValue).sum();
    }

    public LocalDate expiryOf(String batchNo) {
        return batches.stream().filter(b -> b.batchNo().equals(batchNo)).map(BatchData::expiryDate).findFirst().orElse(null);
    }

    /** Typical daily forecast error (never below 10% of demand, to stay cautious). */
    public double dailyErrorStd() {
        return Math.max(prediction.errorStd(), 0.1 * prediction.average(14));
    }
}
