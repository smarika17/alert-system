package com.smarika.heartbeat.website;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;

@Service
public class WebsiteChecker {

    private final WebClient webClient;

    public WebsiteChecker(
            WebClient webClient
    ) {
        this.webClient = webClient;
    }


    public WebsiteCheckResult check(
            String url
    ) {

        Instant start =
                Instant.now();

        try {

            Integer statusCode =
                    webClient
                            .get()
                            .uri(url)
                            .exchangeToMono(
                                    response ->
                                            response
                                                    .toBodilessEntity()
                                                    .map(
                                                            entity ->
                                                                    entity
                                                                            .getStatusCode()
                                                                            .value()
                                                    )
                            )
                            .timeout(
                                    Duration.ofSeconds(5)
                            )
                            .block();


            Instant end =
                    Instant.now();


            boolean success =
                    statusCode != null
                            && statusCode >= 200
                            && statusCode < 300;


            return new WebsiteCheckResult(
                    success,
                    statusCode == null
                            ? 0
                            : statusCode,
                    end,
                    Duration.between(
                            start,
                            end
                    ),
                    success
                            ? null
                            : "HTTP status "
                            + statusCode
            );


        } catch (Exception e) {

            Instant end =
                    Instant.now();

            return new WebsiteCheckResult(
                    false,
                    0,
                    end,
                    Duration.between(
                            start,
                            end
                    ),
                    e.getMessage()
            );
        }
    }
}