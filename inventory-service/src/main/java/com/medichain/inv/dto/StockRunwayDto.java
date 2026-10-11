package com.medichain.inv.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Data contracts for the AI Stock Runway & Depletion Predictor.
 * (Mirrors the contract in Forecast Service for REST communication).
 */
public class StockRunwayDto {

    public record RunwayTrajectoryPoint(
            int dayIndex,
            LocalDate date,
            int stockAiSeasonal,
            int stockBaseline,
            int stockSurge,
            double demandAiSeasonal,
            double demandBaseline
    ) {}

    public record BatchDepletionDetail(
            String batchNo,
            int initialQuantity,
            LocalDate expiryDate,
            int daysUntilExpiry,
            int unitsConsumed,
            int unitsExpiredUnused,
            LocalDate depletedDate,
            String status
    ) {}

    public record StockRunwayReport(
            Long facilityId,
            String facilityName,
            Long medicineId,
            String medicineName,
            String unit,
            String diseaseTag,
            String diseaseName,
            LocalDate asOf,
            int usableStock,
            int activeBatchesCount,
            Integer daysRemaining,           // Days stock will last under AI seasonal forecast
            LocalDate depletionDate,          // Exact date it runs out
            double runwayWeeks,
            String riskLevel,                 // CRITICAL, HIGH, MEDIUM, LOW
            Integer baselineDaysRemaining,   // How long stock would last without seasonal surge
            LocalDate baselineDepletionDate,
            Integer daysLostToSeason,         // baselineDaysRemaining - daysRemaining
            Integer surgeDaysRemaining,      // High outbreak surge scenario
            LocalDate surgeDepletionDate,
            double baseDemandPerDay,
            double seasonalDemandPerDay,
            double seasonalityMultiplier,     // e.g. 1.65
            double seasonalSurgePct,          // e.g. +65%
            double diseaseWeight,             // 0.0 to 1.0
            double rSquared,
            double casesPast2WeeksChangePct,
            double casesNext2WeeksChangePct,
            double avgDemandLast14Days,
            double avgDemandLast30Days,
            double avgDemandLast60Days,
            double consumptionTrendPct,
            List<RunwayTrajectoryPoint> trajectory, // up to 90 days
            List<BatchDepletionDetail> batchDetails,
            List<String> aiInsights
    ) {}

    public record DistrictRunwaySummary(
            String district,
            LocalDate asOf,
            int totalMonitored,
            int criticalDepletionCount,
            int highDepletionCount,
            List<StockRunwayReport> items
    ) {}

    public record RunwaySimulationRequest(
            String district,
            Long facilityId,
            Long medicineId,
            int additionalStock,
            double surgeMultiplierOverride
    ) {}
}
