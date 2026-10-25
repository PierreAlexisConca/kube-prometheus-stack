package com.vallegrande.telemed.dispensary.client;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

@Component
public class RecipeClient {

    private static final Logger log = LoggerFactory.getLogger(RecipeClient.class);

    private final WebClient webClient;
    private final Duration timeout;
    private final Counter externalCallFailuresCounter;
    private final Counter timeoutCounter;

    public RecipeClient(@Value("${services.recipe.url:http://recipe-service:8081}") String recipeServiceUrl,
                        @Value("${services.recipe.timeout-ms:2500}") long timeoutMs,
                        MeterRegistry registry) {
        this.timeout = Duration.ofMillis(timeoutMs);

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(this.timeout);

        this.webClient = WebClient.builder()
                .baseUrl(recipeServiceUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();

        this.externalCallFailuresCounter = Counter.builder("dispensary_external_call_failures_total")
                .description("Total de fallos de comunicacion hacia el servicio de recetas")
                .tag("target_service", "recipe-service")
                .register(registry);

        this.timeoutCounter = Counter.builder("dispensary_timeouts_total")
                .description("Total de timeouts experimentados al consultar recipe-service")
                .tag("target_service", "recipe-service")
                .register(registry);

        log.info("RecipeClient configurado hacia {} con timeout de {} ms", recipeServiceUrl, timeoutMs);
    }

    public Optional<Map<String, Object>> getRecipeById(String recipeId) {
        try {
            return webClient.get()
                    .uri("/api/recipes/{id}", recipeId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response -> {
                        log.warn("Receta {} no encontrada o peticion invalida en recipe-service (HTTP {})",
                                recipeId, response.statusCode());
                        return Mono.empty();
                    })
                    .bodyToMono(Map.class)
                    .timeout(this.timeout)
                    .map(map -> (Map<String, Object>) map)
                    .onErrorResume(TimeoutException.class, ex -> {
                        timeoutCounter.increment();
                        externalCallFailuresCounter.increment();
                        log.error("[TIMEOUT] Tiempo de espera agotado al consultar receta {} tras {} ms: {}",
                                recipeId, timeout.toMillis(), ex.getMessage());
                        return Mono.empty();
                    })
                    .onErrorResume(Exception.class, ex -> {
                        externalCallFailuresCounter.increment();
                        log.error("[ERROR] Fallo de comunicacion con recipe-service: {}", ex.getMessage());
                        return Mono.empty();
                    })
                    .blockOptional();
        } catch (Exception e) {
            externalCallFailuresCounter.increment();
            log.error("Excepcion no controlada invocando recipe-service: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean markRecipeAsDispensed(String recipeId) {
        try {
            Boolean success = webClient.put()
                    .uri("/api/recipes/{id}/dispense", recipeId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(this.timeout)
                    .map(res -> true)
                    .onErrorResume(Exception.class, ex -> {
                        externalCallFailuresCounter.increment();
                        log.error("No se pudo marcar receta {} como dispensada en recipe-service: {}",
                                recipeId, ex.getMessage());
                        return Mono.just(false);
                    })
                    .block();
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            externalCallFailuresCounter.increment();
            return false;
        }
    }
}
