package com.medichain.inv.service;

import com.medichain.inv.dto.ForecastRequest;
import com.medichain.inv.dto.ForecastResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * REST call to the Forecast Service (inter-service communication):
 *   POST http://localhost:8082/api/forecast   body = ForecastRequest  ->  ForecastResponse
 */
@Service
public class ForecastClient {

    private final RestClient forecastRestClient;

    public ForecastClient(RestClient forecastRestClient) {
        this.forecastRestClient = forecastRestClient;
    }

    public ForecastResponse analyse(ForecastRequest request) {
        return forecastRestClient.post()
                .uri("/api/forecast")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ForecastResponse.class);
    }

    public com.medichain.inv.dto.StockRunwayDto.DistrictRunwaySummary getRunwaySummary(ForecastRequest request) {
        return forecastRestClient.post()
                .uri("/api/forecast/runway")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(com.medichain.inv.dto.StockRunwayDto.DistrictRunwaySummary.class);
    }

    public com.medichain.inv.dto.StockRunwayDto.StockRunwayReport simulateRunway(ForecastRequest request, Long facilityId,
                                                                                  Long medicineId, int additionalStock,
                                                                                  double surgeMultiplierOverride) {
        return forecastRestClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/forecast/runway/simulate")
                        .queryParam("facilityId", facilityId)
                        .queryParam("medicineId", medicineId)
                        .queryParam("additionalStock", additionalStock)
                        .queryParam("surgeMultiplierOverride", surgeMultiplierOverride)
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(com.medichain.inv.dto.StockRunwayDto.StockRunwayReport.class);
    }
}
