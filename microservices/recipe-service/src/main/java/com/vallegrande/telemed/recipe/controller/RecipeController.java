package com.vallegrande.telemed.recipe.controller;

import com.vallegrande.telemed.recipe.model.MedicalRecipe;
import com.vallegrande.telemed.recipe.model.RecipeStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private static final Logger log = LoggerFactory.getLogger(RecipeController.class);

    private final Map<String, MedicalRecipe> recipeRepository = new ConcurrentHashMap<>();

    private final Counter recipesCreatedCounter;
    private final Counter recipesDispensedCounter;
    private final Counter recipeQueriesCounter;
    private final Timer recipeProcessingTimer;
    private final AtomicLong artificialDelayGauge = new AtomicLong(0);

    @Value("${app.delay.ms:0}")
    private long initialDelayMs;

    public RecipeController(MeterRegistry registry) {
        // 1. Counters
        this.recipesCreatedCounter = Counter.builder("recipe_created_total")
                .description("Total de recetas medicas emitidas exitosamente")
                .tag("service", "recipe-service")
                .register(registry);

        this.recipesDispensedCounter = Counter.builder("recipe_dispensed_total")
                .description("Total de recetas que han sido atendidas/dispensadas")
                .tag("service", "recipe-service")
                .register(registry);

        this.recipeQueriesCounter = Counter.builder("recipe_queries_total")
                .description("Total de consultas realizadas sobre recetas medicas")
                .tag("service", "recipe-service")
                .register(registry);

        // 2. Gauges
        registry.gauge("recipe_active_count", Collections.emptyList(), this,
                controller -> controller.countActiveRecipes());

        registry.gauge("recipe_artificial_delay_gauge", Collections.emptyList(), artificialDelayGauge,
                AtomicLong::get);

        // 3. Timer
        this.recipeProcessingTimer = Timer.builder("recipe_processing_duration_seconds")
                .description("Tiempo de procesamiento de operaciones sobre recetas")
                .tag("service", "recipe-service")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @PostConstruct
    public void init() {
        this.artificialDelayGauge.set(initialDelayMs);

        // Sembrar datos de prueba iniciales
        MedicalRecipe r1 = new MedicalRecipe("REC-1001", "Carlos Mendoza", "45871234",
                "Dra. Sofia Alarcon", "CMP-34891", "MED-AMX-500", "Amoxicilina 500mg", 30,
                "Tomar 1 capsula cada 8 horas por 10 dias");
        MedicalRecipe r2 = new MedicalRecipe("REC-1002", "Elena Gomez", "71239845",
                "Dr. Roberto Chang", "CMP-29104", "MED-PAR-500", "Paracetamol 500mg", 20,
                "Tomar 1 tableta cada 8 horas en caso de fiebre");
        MedicalRecipe r3 = new MedicalRecipe("REC-1003", "Juan Perez", "10492837",
                "Dra. Sofia Alarcon", "CMP-34891", "MED-IBU-400", "Ibuprofeno 400mg", 15,
                "Tomar 1 tableta con alimentos cada 12 horas");

        recipeRepository.put(r1.getId(), r1);
        recipeRepository.put(r2.getId(), r2);
        recipeRepository.put(r3.getId(), r3);

        recipesCreatedCounter.increment(3);
        log.info("RecipeController inicializado con 3 recetas de prueba y delay inicial: {} ms", initialDelayMs);
    }

    private double countActiveRecipes() {
        return recipeRepository.values().stream()
                .filter(r -> r.getStatus() == RecipeStatus.ACTIVE)
                .count();
    }

    private void applyArtificialDelay() {
        long delay = artificialDelayGauge.get();
        if (delay > 0) {
            try {
                log.warn("[Casuistica 2] Aplicando delay artificial de {} ms en respuesta de receta", delay);
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Endpoint 1: Crear receta medica
    @PostMapping
    public ResponseEntity<MedicalRecipe> createRecipe(@RequestBody MedicalRecipe recipe) {
        return recipeProcessingTimer.record(() -> {
            applyArtificialDelay();

            if (recipe.getId() == null || recipe.getId().isBlank()) {
                recipe.setId("REC-" + (1000 + recipeRepository.size() + 1));
            }
            recipe.setStatus(RecipeStatus.ACTIVE);
            recipe.setCreatedAt(LocalDateTime.now());

            recipeRepository.put(recipe.getId(), recipe);
            recipesCreatedCounter.increment();

            log.info("Nueva receta emitida: id={}, paciente={}, medicamento={}",
                    recipe.getId(), recipe.getPatientName(), recipe.getMedicationName());
            return ResponseEntity.status(HttpStatus.CREATED).body(recipe);
        });
    }

    // Endpoint 2: Listar todas las recetas con filtro opcional por estado
    @GetMapping
    public ResponseEntity<List<MedicalRecipe>> getAllRecipes(@RequestParam(required = false) RecipeStatus status) {
        return recipeProcessingTimer.record(() -> {
            applyArtificialDelay();
            recipeQueriesCounter.increment();

            if (status != null) {
                List<MedicalRecipe> filtered = recipeRepository.values().stream()
                        .filter(r -> r.getStatus() == status)
                        .toList();
                return ResponseEntity.ok(filtered);
            }
            return ResponseEntity.ok(new ArrayList<>(recipeRepository.values()));
        });
    }

    // Endpoint 3: Consultar receta por ID (utilizado por dispensary-service)
    @GetMapping("/{id}")
    public ResponseEntity<?> getRecipeById(@PathVariable String id) {
        return recipeProcessingTimer.record(() -> {
            applyArtificialDelay();
            recipeQueriesCounter.increment();

            MedicalRecipe recipe = recipeRepository.get(id);
            if (recipe == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Receta medica no encontrada", "id", id));
            }
            return ResponseEntity.ok(recipe);
        });
    }

    // Endpoint 4: Marcar receta como DISPENSED
    @PutMapping("/{id}/dispense")
    public ResponseEntity<?> markAsDispensed(@PathVariable String id) {
        return recipeProcessingTimer.record(() -> {
            applyArtificialDelay();

            MedicalRecipe recipe = recipeRepository.get(id);
            if (recipe == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Receta medica no encontrada para dispensacion", "id", id));
            }

            if (recipe.getStatus() == RecipeStatus.DISPENSED) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "La receta ya fue previamente dispensada", "id", id));
            }

            recipe.setStatus(RecipeStatus.DISPENSED);
            recipe.setDispensedAt(LocalDateTime.now());
            recipesDispensedCounter.increment();

            log.info("Receta {} actualizada exitosamente a estado DISPENSED", id);
            return ResponseEntity.ok(recipe);
        });
    }

    // Endpoint 5: Resumen de estadisticas de recetas
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        recipeQueriesCounter.increment();
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRecipes", recipeRepository.size());
        stats.put("activeRecipes", countActiveRecipes());
        stats.put("dispensedRecipes", recipeRepository.values().stream()
                .filter(r -> r.getStatus() == RecipeStatus.DISPENSED).count());
        stats.put("currentDelayMs", artificialDelayGauge.get());
        return ResponseEntity.ok(stats);
    }

    // Endpoint auxiliar: Configurar delay artificial en caliente para Casuistica 2
    @PostMapping("/config/delay")
    public ResponseEntity<Map<String, Object>> setArtificialDelay(@RequestParam long ms) {
        artificialDelayGauge.set(ms);
        log.warn("Latencia artificial actualizada a {} ms", ms);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "configuredDelayMs", ms));
    }

    // Endpoint auxiliar: Provocar fallo de salud para Casuistica 1
    @PostMapping("/config/simulate-crash")
    public ResponseEntity<Map<String, Object>> setSimulateCrash(@RequestParam boolean enable) {
        HealthController.setManualFailureTrigger(enable);
        log.error("Simulacion de fallo de salud establecida a: {}", enable);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "simulateCrash", enable));
    }
}
