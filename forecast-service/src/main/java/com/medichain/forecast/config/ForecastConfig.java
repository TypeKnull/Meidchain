package com.medichain.forecast.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Planning parameters (from application.properties). */
@Component
public class ForecastConfig {

    /** Don't suggest transfers between facilities further apart than this (road km). */
    public final double maxTransferKm;
    /** Extra days of cover kept on top of the supplier lead time. */
    public final int bufferDays;
    /** Safety-stock factor: 1.65 = 95% service level. */
    public final double serviceLevelZ;

    public ForecastConfig(@Value("${medichain.max-transfer-distance-km:75}") double maxTransferKm,
                          @Value("${medichain.safety-buffer-days:7}") int bufferDays,
                          @Value("${medichain.service-level-z:1.65}") double serviceLevelZ) {
        this.maxTransferKm = maxTransferKm;
        this.bufferDays = bufferDays;
        this.serviceLevelZ = serviceLevelZ;
    }
}
