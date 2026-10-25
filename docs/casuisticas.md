# Guía de Casuísticas de Monitoreo — Telemedicina & Farmacia Digital

Este documento detalla las tres casuísticas problemáticas diseñadas para evidenciar el valor del stack de observabilidad con **Prometheus, Grafana y Kubernetes**.

---

## 🔴 Casuística 1 — El Servicio Caído (CrashLoopBackOff y Fallo de Probes)

### 1. Contexto Narrativo
Durante un despliegue nocturno, se actualizó una variable de configuración de entorno errónea en el microservicio de recetas (`recipe-service`), haciendo que el servicio falle sistemáticamente en su inicialización o no logre responder satisfactoriamente a las comprobaciones de salud del cluster. Los usuarios finales no pueden consultar ni emitir recetas médicas.

### 2. Síntoma
- El frontend o los clientes externos que intentan consultar `http://localhost:30081/api/recipes` reciben errores de conexión (`Connection Refused` o `503 Service Unavailable`).
- El servicio de farmacia (`dispensary-service`) no puede validar recetas y activa su mecanismo de contingencia offline.
- El pod de `recipe-service` entra en estado `CrashLoopBackOff` o pierde su condición `Ready`.

### 3. Activación
Ejecuta el siguiente comando en PowerShell para inyectar la variable de fallo en el deployment de Kubernetes:
```powershell
kubectl set env deployment/recipe-deployment -n telemed-ns SIMULATE_FAILURE=true
```

### 4. Diagnóstico
1. **En Kubernetes CLI:**
   ```powershell
   kubectl get pods -n telemed-ns
   # Salida: recipe-deployment-xxxx   0/1   CrashLoopBackOff o Running (0/1 Ready)
   kubectl describe pod -l app=recipe-service -n telemed-ns
   # En Events se observa: Liveness probe failed: HTTP probe failed with statuscode: 503
   ```
2. **En Prometheus:**
   - En `http://localhost:9090/alerts`, la regla `RecipeServiceDown` pasa a estado **FIRING**.
   - En `http://localhost:9090/targets`, el target `recipe-servicemonitor` pasa a estado **DOWN**.
3. **En Grafana:**
   - El panel **1. Estado de Salud de Microservicios** cambia inmediatamente a color rojo indicando **DOWN** para `recipe-service`.
   - El panel **4. Tasa de Peticiones HTTP** muestra una caída a 0 req/s para peticiones 2xx.

### 5. Resolución
Restaura la variable de entorno al valor correcto:
```powershell
kubectl set env deployment/recipe-deployment -n telemed-ns SIMULATE_FAILURE=false
kubectl rollout status deployment/recipe-deployment -n telemed-ns
```

### 6. Lección Técnica
- **Liveness vs Readiness Probe:**
  - El **Readiness Probe** determina cuándo un pod está listo para recibir tráfico del Service. Si falla, Kubernetes simplemente retira el pod de los endpoints del Service sin reiniciarlo.
  - El **Liveness Probe** determina si la aplicación dentro del contenedor está viva o en un estado irrecuperable (deadlock, fallo interno permanente). Si el liveness probe falla repetidamente (`failureThreshold: 3`), el kubelet **reinicia el contenedor**. Al fallar en cada inicio, Kubernetes aplica un retroceso exponencial (*exponential backoff*), resultando en el estado `CrashLoopBackOff`.
- Sin observabilidad proactiva, este problema sólo se detectaría cuando los pacientes reporten la imposibilidad de recibir medicamentos.

---

## 🟡 Casuística 2 — El Servicio Lento (Propagación de Latencia y Timeouts)

### 1. Contexto Narrativo
El microservicio `recipe-service` sufre una degradación de rendimiento severa (simulada mediante un delay artificial configurable). El microservicio `dispensary-service` intenta consultar las recetas pero tiene un timeout configurado de **2500 ms** en su cliente `Spring WebClient`. Cuando el tiempo de respuesta de `recipe-service` supera dicho umbral, `dispensary-service` experimenta timeouts en cascada, activa un fallback controlado y responde al usuario con HTTP 504 / mensaje de contingencia.

### 2. Síntoma
- El usuario intenta dispensar un medicamento en `dispensary-service`. La petición tarda 2.5 segundos y retorna un pedido en estado `TIMEOUT_FALLBACK` en lugar de `COMPLETED`.
- A nivel de negocio, la farmacia no puede despachar el medicamento en línea.

### 3. Activación
Configura un delay artificial de **3000 ms** (3 segundos) en `recipe-service`, el cual supera el timeout de 2.5 segundos de `dispensary-service`:
```powershell
kubectl set env deployment/recipe-deployment -n telemed-ns DELAY_MS=3000
```
Posteriormente, ejecuta una ráfaga de peticiones para evidenciar el problema:
```powershell
.\load-testing\stress.ps1 -MinikubeIp "localhost"
```

### 4. Diagnóstico
1. **En Grafana:**
   - El panel **2. Delay Artificial en Recipe Service (Gauge)** sube en tiempo real a **3 s**, entrando en la zona roja de advertencia.
   - El panel **3. Fallos y Timeouts de Comunicación Inter-servicio** muestra un incremento en el contador de fallos.
   - El panel **5. Latencia Percentil 95 (P95)** se dispara a valores superiores a 3 segundos.
2. **En Prometheus:**
   - La alerta `HighRecipeServiceLatency` pasa a estado **FIRING** debido a la expresión `recipe_artificial_delay_gauge > 2000`.

### 5. Resolución
Restablece el delay artificial a 0 ms:
```powershell
kubectl set env deployment/recipe-deployment -n telemed-ns DELAY_MS=0
```

### 6. Lección Técnica
- **Propagación de latencia (*Cascading Failures*):** En una arquitectura de microservicios, el retraso de un servicio aguas abajo impacta directamente a todos los servicios que dependen de él.
- **Circuit Breaker y Timeouts Estrictos:** Es mandatorio configurar timeouts en cada cliente HTTP (`WebClient`) para evitar que los hilos de ejecución queden bloqueados indefinidamente, agotando el pool de conexiones del servicio llamante.
- **Observabilidad como aislador de culpa:** El panel de latencia P95 y el Gauge de delay permiten identificar instantáneamente que el culpable es `recipe-service` y no `dispensary-service`.

---

## 🟠 Casuística 3 — Agotamiento de Stock Farmacéutico e Inconsistencia de Negocio

### 1. Contexto Narrativo
Se produce un brote infeccioso o una alta demanda imprevista que agota rápidamente las existencias del medicamento crítico `MED-IBU-400` (Ibuprofeno 400mg) en la farmacia digital. El servicio `dispensary-service` valida con éxito la receta en `recipe-service`, pero al verificar su inventario interno detecta stock insuficiente, rechazando las dispensaciones con estado `REJECTED_STOCK` (HTTP 422).

### 2. Síntoma
- El paciente tiene una receta médica válida emitida por su médico, pero al acudir o solicitar el despacho de su medicina, el sistema rechaza la solicitud indicando `Rechazado: Stock insuficiente`.
- Se generan rechazos de respuesta controlada (HTTP 422) en las llamadas a la API.

### 3. Activación
Ejecuta el script de carga continua para consumir las 10 unidades iniciales de Ibuprofeno:
```powershell
.\load-testing\stress.ps1 -MinikubeIp "localhost"
```

### 4. Diagnóstico
1. **En Grafana:**
   - En el panel **6. Flujo de Negocio**, la serie **Rechazos por Stock (Casuística 3)** muestra un salto positivo en amarillo, reflejando el conteo de fallos de inventario (HTTP 422).
   - En el panel **7. Niveles de Stock de Medicamentos en Farmacia**, la barra de `MED-IBU-400` cae a **0** y se torna de color rojo.
2. **En Prometheus:**
   - La alerta `PharmacyCriticalLowStock` pasa a estado **FIRING** debido a la expresión `dispensary_stock_levels < 5`.

### 5. Resolución
El equipo de logística realiza la reposición de emergencia en la farmacia digital utilizando el endpoint de restock:
```powershell
Invoke-RestMethod -Uri "http://localhost:30082/api/dispensations/pharmacy/restock?medicationCode=MED-IBU-400&amount=50" -Method Post
```
Al reponer 50 unidades, el gauge de stock sube automáticamente a niveles seguros y la alerta se resuelve.

### 6. Lección Técnica
- **Métricas de Negocio vs Métricas de Infraestructura:** La infraestructura (CPU, memoria, pods) puede estar 100% saludable, pero el negocio puede estar fallando por completo. Las métricas de aplicación instrumentadas con Micrometer permiten a los ingenieros de confiabilidad (SRE) detectar problemas operativos antes de que causen impacto financiero masivo.