package com.medichain.inv.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Creates the HTTP client used to call the Forecast Service. */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient forecastRestClient(RestClient.Builder builder,
                                         @Value("${forecast.base-url}") String forecastBaseUrl) {
        return builder.baseUrl(forecastBaseUrl).build();
    }
}
