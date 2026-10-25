package com.vallegrande.telemed.dispensary.controller;

import com.vallegrande.telemed.dispensary.client.RecipeClient;
import com.vallegrande.telemed.dispensary.model.DispensationOrder;
import com.vallegrande.telemed.dispensary.model.DispensationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/dispensations")
public class DispensaryController {

    private static final Logger log = LoggerFactory.getLogger(DispensaryController.class);

    private final RecipeClient recipeClient;
    private final MeterRegistry meterRegistry;

    // Repositorio en memoria de dispensaciones
    private final Map<String, DispensationOrder> dispensationRepository = new ConcurrentHashMap<>();

    // Inventario farmaceutico en memoria
    private final Map<String, AtomicInteger> pharmacyInventory = new ConcurrentHashMap<>();

    // Metricas Micrometer
    private final Counter ordersCompletedCounter;
    private final Counter ordersRejectedStockCounter;
    private final Counter ordersTimeoutFallbackCounter;
    private final Counter ordersQueriesCounter;
    private final Timer orderProcessingTimer;

    public DispensaryController(RecipeClient recipeClient, MeterRegistry registry) {
        this.recipeClient = recipeClient;
        this.meterRegistry = registry;

        // 1. Counters por estado de negocio
        this.ordersCompletedCounter = Counter.builder("dispensary_orders_total")
                .description("Total de dispensaciones farmaceuticas procesadas exitosamente")
                .tag("service", "dispensary-service")
                .tag("status", "COMPLETED")
                .register(registry);

        this.ordersRejectedStockCounter = Counter.builder("dispensary_orders_total")
                .description("Dispensaciones rechazadas por falta de inventario farmaceutico")
                .tag("service", "dispensary-service")
                .tag("status", "REJECTED_STOCK")
                .register(registry);

        this.ordersTimeoutFallbackCounter = Counter.builder("dispensary_orders_total")
                .description("Dispensaciones que activaron fallback debido a timeout o caida del servicio A")
                .tag("service", "dispensary-service")
                .tag("status", "TIMEOUT_FALLBACK")
                .register(registry);

        this.ordersQueriesCounter = Counter.builder("dispensary_queries_total")
                .description("Total de consultas de inventario y pedidos")
                .tag("service", "dispensary-service")
                .register(registry);

        // 2. Gauges
        registry.gauge("dispensary_total_orders_gauge", Collections.emptyList(), dispensationRepository,
                Map::size);

        // 3. Timer
        this.orderProcessingTimer = Timer.builder("dispensary_order_processing_time_seconds")
                .description("Duracion total del proceso de dispensacion e integracion con Servicio A")
                .tag("service", "dispensary-service")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @PostConstruct
    public void init() {
        // Inicializar stock de farmacia con medicamentos clave
        registerStockItem("MED-AMX-500", 60);  // Amoxicilina 500mg
        registerStockItem("MED-PAR-500", 100); // Paracetamol 500mg
        registerStockItem("MED-IBU-400", 10);  // Ibuprofeno 400mg (stock bajo para pruebas)

        log.info("DispensaryController inicializado con inventario inicial en memoria");
    }

    private void registerStockItem(String medicationCode, int initialStock) {
        AtomicInteger stock = new AtomicInteger(initialStock);
        pharmacyInventory.put(medicationCode, stock);

        // Registrar Gauge individual por codigo de medicamento
        meterRegistry.gauge("dispensary_stock_levels",
                List.of(io.micrometer.core.instrument.Tag.of("medication_code", medicationCode)),
                stock, AtomicInteger::get);
    }

    public record DispensationRequest(String recipeId, String patientDni) {}

    // Endpoint 1: Procesar dispensacion consumiendo recipe-service via WebClient
    @PostMapping
    public ResponseEntity<?> processDispensation(@RequestBody DispensationRequest request) {
        return orderProcessingTimer.record(() -> {
            log.info("Iniciando solicitud de dispensacion para receta ID: {}", request.recipeId());

            // Paso 1: Consultar receta en el Servicio A con timeout controlado
            Optional<Map<String, Object>> recipeOpt = recipeClient.getRecipeById(request.recipeId());

            String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            // Manejo de Timeout o Caida del Servicio A (Casuistica 2)
            if (recipeOpt.isEmpty()) {
                ordersTimeoutFallbackCounter.increment();
                DispensationOrder fallbackOrder = new DispensationOrder(
                        orderId,
                        request.recipeId(),
                        "DESCONOCIDO",
                        "N/A",
                        "N/A",
                        0,
                        DispensationStatus.TIMEOUT_FALLBACK,
                        "Servicio de recetas no disponible o tiempo de espera agotado. Activado plan de contingencia offline."
                );
                dispensationRepository.put(orderId, fallbackOrder);

                log.warn("[FALLBACK] Dispensacion {} registrada con fallback por timeout con recipe-service", orderId);
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(fallbackOrder);
            }

            Map<String, Object> recipe = recipeOpt.get();
            String status = (String) recipe.get("status");
            String medicationCode = (String) recipe.get("medicationCode");
            String medicationName = (String) recipe.get("medicationName");
            String patientName = (String) recipe.get("patientName");
            int quantity = ((Number) recipe.getOrDefault("quantity", 1)).intValue();

            // Paso 2: Validar si la receta ya fue dispensada
            if ("DISPENSED".equalsIgnoreCase(status)) {
                DispensationOrder rejectedOrder = new DispensationOrder(
                        orderId, request.recipeId(), patientName, medicationCode, medicationName,
                        quantity, DispensationStatus.REJECTED_ALREADY_DISPENSED,
                        "Rechazado: La receta ya figura como dispensada en el sistema central"
                );
                dispensationRepository.put(orderId, rejectedOrder);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(rejectedOrder);
            }

            // Paso 3: Validar stock en la farmacia (Casuistica 3)
            AtomicInteger currentStock = pharmacyInventory.get(medicationCode);
            if (currentStock == null || currentStock.get() < quantity) {
                ordersRejectedStockCounter.increment();
                int available = (currentStock == null) ? 0 : currentStock.get();
                DispensationOrder stockRejected = new DispensationOrder(
                        orderId, request.recipeId(), patientName, medicationCode, medicationName,
                        quantity, DispensationStatus.REJECTED_STOCK,
                        String.format("Rechazado: Stock insuficiente. Requerido: %d, Disponible: %d", quantity, available)
                );
                dispensationRepository.put(orderId, stockRejected);
                log.warn("[CASUISTICA 3] Stock insuficiente para {}. Disponible: {}, Requerido: {}",
                        medicationCode, available, quantity);
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(stockRejected);
            }

            // Paso 4: Descontar stock y actualizar receta en Servicio A
            currentStock.addAndGet(-quantity);
            boolean marked = recipeClient.markRecipeAsDispensed(request.recipeId());
            if (!marked) {
                log.warn("Stock descontado pero recipe-service no confirmo actualizacion de receta {}", request.recipeId());
            }

            DispensationOrder completedOrder = new DispensationOrder(
                    orderId, request.recipeId(), patientName, medicationCode, medicationName,
                    quantity, DispensationStatus.COMPLETED,
                    "Dispensacion farmaceutica exitosa. Medicamento entregado."
            );
            dispensationRepository.put(orderId, completedOrder);
            ordersCompletedCounter.increment();

            log.info("Dispensacion {} completada exitosamente para paciente {}", orderId, patientName);
            return ResponseEntity.status(HttpStatus.CREATED).body(completedOrder);
        });
    }

    // Endpoint 2: Listar todas las ordenes de dispensacion
    @GetMapping
    public ResponseEntity<List<DispensationOrder>> getAllDispensations(
            @RequestParam(required = false) DispensationStatus status) {
        ordersQueriesCounter.increment();
        if (status != null) {
            List<DispensationOrder> filtered = dispensationRepository.values().stream()
                    .filter(o -> o.getStatus() == status)
                    .toList();
            return ResponseEntity.ok(filtered);
        }
        return ResponseEntity.ok(new ArrayList<>(dispensationRepository.values()));
    }

    // Endpoint 3: Consultar orden especifica por ID
    @GetMapping("/{id}")
    public ResponseEntity<?> getDispensationById(@PathVariable String id) {
        ordersQueriesCounter.increment();
        DispensationOrder order = dispensationRepository.get(id);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Orden de dispensacion no encontrada", "id", id));
        }
        return ResponseEntity.ok(order);
    }

    // Endpoint 4: Consultar inventario farmaceutico en tiempo real
    @GetMapping("/pharmacy/inventory")
    public ResponseEntity<Map<String, Integer>> getInventory() {
        ordersQueriesCounter.increment();
        Map<String, Integer> inventoryMap = new HashMap<>();
        pharmacyInventory.forEach((code, atomic) -> inventoryMap.put(code, atomic.get()));
        return ResponseEntity.ok(inventoryMap);
    }

    // Endpoint 5: Reponer stock de medicamento (util para pruebas y recuperacion)
    @PostMapping("/pharmacy/restock")
    public ResponseEntity<Map<String, Object>> restock(
            @RequestParam String medicationCode,
            @RequestParam int amount) {
        AtomicInteger stock = pharmacyInventory.computeIfAbsent(medicationCode, code -> {
            AtomicInteger newStock = new AtomicInteger(0);
            meterRegistry.gauge("dispensary_stock_levels",
                    List.of(io.micrometer.core.instrument.Tag.of("medication_code", code)),
                    newStock, AtomicInteger::get);
            return newStock;
        });

        int newTotal = stock.addAndGet(amount);
        log.info("Reposicion de stock para {}: +{} unidades. Total actual: {}", medicationCode, amount, newTotal);

        return ResponseEntity.ok(Map.of(
                "medicationCode", medicationCode,
                "added", amount,
                "currentStock", newTotal
        ));
    }

    // Endpoint auxiliar: Provocar fallo de salud para Casuistica 1
    @PostMapping("/config/simulate-crash")
    public ResponseEntity<Map<String, Object>> setSimulateCrash(@RequestParam boolean enable) {
        HealthController.setManualFailureTrigger(enable);
        log.error("Simulacion de fallo de salud en dispensary-service: {}", enable);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "simulateCrash", enable));
    }
}
