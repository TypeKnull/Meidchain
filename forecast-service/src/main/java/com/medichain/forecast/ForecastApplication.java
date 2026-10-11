package com.medichain.forecast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microservice 2 - Forecast Service (port 8082). The "AI brain".
 *
 * It has NO database. The Inventory Service sends it all the data in one
 * ForecastRequest and gets back a ForecastResponse with:
 * demand forecast, stock-out risk, expiry risk, transfers and purchase orders.
 */
@SpringBootApplication
public class ForecastApplication {
    public static void main(String[] args) {
        SpringApplication.run(ForecastApplication.class, args);
    }
}
