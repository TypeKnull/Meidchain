package com.medichain.forecast.controller;

import com.medichain.forecast.dto.ForecastRequest;
import com.medichain.forecast.dto.ForecastResponse;
import com.medichain.forecast.service.ForecastService;
import org.springframework.web.bind.annotation.*;

/**
 * The only endpoint of the Forecast Service.
 * Called by the Inventory Service:  POST http://localhost:8082/api/forecast
 */
@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final ForecastService forecastService;
    private final com.medichain.forecast.service.StockRunwayService runwayService;

    public ForecastController(ForecastService forecastService,
                              com.medichain.forecast.service.StockRunwayService runwayService) {
        this.forecastService = forecastService;
        this.runwayService = runwayService;
    }

    @PostMapping
    public ForecastResponse analyse(@RequestBody ForecastRequest request) {
        return forecastService.analyse(request);
    }

    /** AI Stock Duration & Depletion Predictor: District-wide runway summary */
    @PostMapping("/runway")
    public com.medichain.forecast.dto.StockRunwayDto.DistrictRunwaySummary runwaySummary(@RequestBody ForecastRequest request) {
        return runwayService.calculateDistrictSummary(request);
    }

    /** What-If Simulator: recalculates stock runway with hypothetical stock additions or disease surges */
    @PostMapping("/runway/simulate")
    public com.medichain.forecast.dto.StockRunwayDto.StockRunwayReport simulateRunway(
            @RequestBody ForecastRequest request,
            @RequestParam Long facilityId,
            @RequestParam Long medicineId,
            @RequestParam(defaultValue = "0") int additionalStock,
            @RequestParam(defaultValue = "1.0") double surgeMultiplierOverride) {
        return runwayService.simulateWhatIf(request, facilityId, medicineId, additionalStock, surgeMultiplierOverride);
    }

    /** Quick check that the service is up: GET http://localhost:8082/api/forecast/health */
    @GetMapping("/health")
    public String health() {
        return "Forecast service is running";
    }
}
