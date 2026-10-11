package com.medichain.inv.controller;

import com.medichain.inv.dto.ForecastResponse;
import com.medichain.inv.dto.ForecastResponse.RiskRow;
import com.medichain.inv.service.QueryService;
import com.medichain.inv.service.StockService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;

import java.util.*;

/** The AI features: ask a question, and the dashboard. Both call the Forecast Service. */
@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final QueryService queryService;
    private final StockService stockService;

    public AnalysisController(QueryService queryService, StockService stockService) {
        this.queryService = queryService;
        this.stockService = stockService;
    }

    public record Question(String question, String district) { }

    /** Example body: {"question": "Which PHCs will run out of ORS in the next 10 days?", "district": "Vellore"} */
    @PostMapping("/query")
    public QueryService.QueryResult ask(@RequestBody Question q) {
        return queryService.ask(q.question(), q.district());
    }

    /** Dashboard = last 90 days from the database + the current risk picture from the AI. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam String district) {
        ForecastResponse now = queryService.analyse(district, List.of(), 14);
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (String level : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) levels.put(level, 0);
        for (RiskRow r : now.risks()) levels.merge(r.riskLevel(), 1, Integer::sum);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("district", district);
        out.put("asOf", now.asOf());
        out.put("history", stockService.history(district, 90));
        out.put("riskLevels", levels);
        out.put("runningOutIn14Days", now.risks().stream().filter(RiskRow::runsOutInHorizon).toList());
        out.put("transfersRecommended", now.transfers().size());
        out.put("expiringUnits", now.expiry().stream().mapToInt(ForecastResponse.ExpiryRow::unitsAtRisk).sum());
        out.put("ordersSuggested", now.orders().size());
        return out;
    }

    /** Returns weekly surveillance cases for seasonal epidemiology trends. */
    @GetMapping("/disease-trends")
    public Map<String, Object> diseaseTrends(@RequestParam String district) {
        return stockService.diseaseTrends(district);
    }

    /**
     * AI Stock Runway & Depletion Predictor:
     * - If facilityId and medicineId are provided: returns deep-dive runway analysis for that item.
     * - If omitted: returns district-wide summary ranking items by shortest runway.
     */
    @GetMapping("/analytics/stock-runway")
    public Object stockRunway(
            @RequestParam String district,
            @RequestParam(required = false) Long facilityId,
            @RequestParam(required = false) Long medicineId) {
        if (facilityId != null && medicineId != null) {
            return queryService.getRunwayReport(district, facilityId, medicineId);
        }
        return queryService.getRunwaySummary(district);
    }

    /** Interactive What-If simulation: recalculates stock runway with hypothetical stock or surge variations. */
    @PostMapping("/analytics/stock-runway/simulate")
    public com.medichain.inv.dto.StockRunwayDto.StockRunwayReport simulateStockRunway(
            @RequestBody com.medichain.inv.dto.StockRunwayDto.RunwaySimulationRequest req) {
        return queryService.simulateRunway(
                req.district(),
                req.facilityId(),
                req.medicineId(),
                req.additionalStock(),
                req.surgeMultiplierOverride()
        );
    }

    /** Friendly message when the Forecast Service is not running. */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> forecastDown() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Forecast Service is not reachable. Start it first (port 8082)."));
    }
}
