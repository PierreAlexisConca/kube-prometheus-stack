package com.vallegrande.telemed.recipe.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    @Value("${app.simulate.failure:false}")
    private boolean simulateFailure;

    private static boolean manualFailureTrigger = false;

    public static void setManualFailureTrigger(boolean trigger) {
        manualFailureTrigger = trigger;
    }

    public static boolean isManualFailureTrigger() {
        return manualFailureTrigger;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> response = new HashMap<>();
        response.put("service", "recipe-service");
        response.put("timestamp", Instant.now().toString());

        if (simulateFailure || manualFailureTrigger) {
            response.put("status", "DOWN");
            response.put("reason", "Simulated failure active for Casuistica 1 (CrashLoopBackOff)");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        response.put("status", "UP");
        response.put("message", "Service is healthy and ready to process recipes");
        return ResponseEntity.ok(response);
    }
}
