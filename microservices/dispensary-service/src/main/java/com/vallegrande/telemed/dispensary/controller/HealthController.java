package com.vallegrande.telemed.dispensary.controller;

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

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> response = new HashMap<>();
        response.put("service", "dispensary-service");
        response.put("timestamp", Instant.now().toString());

        if (simulateFailure || manualFailureTrigger) {
            response.put("status", "DOWN");
            response.put("reason", "Simulated failure active in dispensary-service");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        response.put("status", "UP");
        response.put("message", "Dispensary service is healthy and operating");
        return ResponseEntity.ok(response);
    }
}
