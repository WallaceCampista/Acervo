package com.acervo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Reporta UP/DOWN do LM Studio (ou outro servidor OpenAI-compat) sondando
 * {@code GET /v1/models}. Aparece em {@code /actuator/health/lmStudio}.
 *
 * <p>Útil pra Docker healthcheck e pra ver rapidamente se a dependência
 * externa de IA está acessível sem precisar disparar uma pergunta.
 */
@Component("lmStudio")
public class LmStudioHealthIndicator implements HealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final String baseUrl;
    private final RestClient client;

    public LmStudioHealthIndicator(@Value("${spring.ai.openai.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public Health health() {
        Health.Builder b = Health.unknown().withDetail("baseUrl", baseUrl);
        long t0 = System.currentTimeMillis();
        try {
            // Sonda /v1/models. Body não importa — só queremos saber se respondeu 2xx.
            client.get().uri("/v1/models")
                    .retrieve()
                    .toBodilessEntity()
                    .toString(); // força execução
            return b.up()
                    .withDetail("latencyMs", System.currentTimeMillis() - t0)
                    .build();
        } catch (Exception e) {
            return b.down()
                    .withDetail("latencyMs", System.currentTimeMillis() - t0)
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}
