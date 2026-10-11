package com.medichain.inv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Microservice 1 - Inventory Service (port 8081).
 * Owns the database and the web UI (http://localhost:8081).
 * For predictions it calls the Forecast Service (port 8082) over REST.
 */
@SpringBootApplication
@EnableScheduling
public class InvApplication {
    public static void main(String[] args) {
        SpringApplication.run(InvApplication.class, args);
    }
}
