# Informe Técnico — Monitoreo de Microservicios en Kubernetes

**Curso:** Kubernetes con Prometheus & Grafana — Valle Grande  
**Dominio de Negocio:** Telemedicina & Farmacia Digital  
**Microservicio A:** `recipe-service` (Gestión de Recetas Médicas Digitales)  
**Microservicio B:** `dispensary-service` (Dispensación Farmacéutica)  
**Autor:** Estudiante Anthony Gala Valle Grande  

## PARTE A — Conceptos Técnicos

### 1. ¿Qué es Micrometer y por qué se usa en lugar de la librería directa de Prometheus?
**Micrometer** es una fachada (*facade*) de métricas para aplicaciones Java, conceptualmente idéntica a lo que `SLF4J` representa para el logging. Proporciona una interfaz unificada y neutral frente a los sistemas de telemetría subyacentes.

**Por qué se usa en lugar del cliente directo de Prometheus (`simpleclient`):**
1. **Desacoplamiento y Portabilidad:** Si una empresa decide migrar o duplicar el envío de métricas de Prometheus hacia Datadog, New Relic, Dynatrace o CloudWatch, con Micrometer basta con cambiar o añadir una dependencia en el `pom.xml` (`micrometer-registry-prometheus`). No es necesario reescribir ni una sola línea de código fuente.
2. **Integración Nativa con Spring Boot Actuator:** Spring Boot 3 autoconfigura automáticamente los contadores de peticiones HTTP (`http.server.requests`), uso de memoria de la JVM, recolector de basura (GC) e hilos de ejecución directamente mediante Micrometer.
3. **Soporte de Dimensionalidad (Tags/Labels):** Permite etiquetar métricas mediante pares clave-valor (por ejemplo, `tag("status", "COMPLETED")`) de manera limpia e idiomática en Java.

### 2. ¿Cuál es la diferencia entre Counter, Gauge y Timer? Da un ejemplo de cada uno tomado de tu código.

| Tipo de Métrica | Comportamiento | Propósito | Ejemplo en nuestro código |
| :--- | :--- | :--- | :--- |
| **Counter (Contador)** | Valor numérico acumulativo que **sólo puede incrementarse** o reiniciarse a 0 al reiniciar el proceso. | Medir la frecuencia o cantidad total de eventos ocurridos. | `recipe_created_total`: Incrementado cada vez que un médico emite una nueva receta médica en `RecipeController.java`. |
| **Gauge (Medidor de nivel)** | Valor numérico instantáneo que **puede subir y bajar** libremente en cualquier momento. | Medir el estado actual o la capacidad de un recurso en un instante dado. | `recipe_artificial_delay_gauge`: Representa el valor instantáneo de latencia artificial inyectada (en ms) o `dispensary_stock_levels` para el stock actual de un medicamento en farmacia. |
| **Timer (Temporizador)** | Mide simultáneamente la **duración** de una operación y la **frecuencia** de ejecuciones (genera percentiles, sumas y conteos). | Medir el rendimiento y latencia de transacciones críticas. | `dispensary_order_processing_time_seconds`: Mide el tiempo total que toma consultar la receta en el Servicio A, validar el stock y procesar el despacho en `DispensaryController.java`. |

#### Extracto del código fuente (`RecipeController.java`):
```java
// Counter
this.recipesCreatedCounter = Counter.builder("recipe_created_total")
        .description("Total de recetas medicas emitidas exitosamente")
        .tag("service", "recipe-service")
        .register(registry);

// Gauge
registry.gauge("recipe_artificial_delay_gauge", Collections.emptyList(), artificialDelayGauge,
        AtomicLong::get);

// Timer
this.recipeProcessingTimer = Timer.builder("recipe_processing_duration_seconds")
        .description("Tiempo de procesamiento de operaciones sobre recetas")
        .tag("service", "recipe-service")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry);
```

### 3. ¿Qué es un ServiceMonitor y cómo sabe el Prometheus Operator qué monitorear?
Un **ServiceMonitor** es un Recurso Personalizado (*Custom Resource Definition* o CRD) introducido por el **Prometheus Operator** que declara declarativamente cómo y cuándo raspar métricas de un conjunto de Pods respaldados por un `Service` de Kubernetes.

**Mecanismo de detección del Prometheus Operator:**
1. El Prometheus Operator vigila constantemente el API Server de Kubernetes en busca de objetos de tipo `ServiceMonitor`.
2. El `ServiceMonitor` especifica tres cosas fundamentales:
   - **`namespaceSelector`:** En qué namespaces buscar servicios (en nuestro caso, `telemed-ns`).
   - **`selector.matchLabels`:** Qué etiquetas debe tener el `Service` de Kubernetes para ser seleccionado (por ejemplo, `app: recipe-service`).
   - **`endpoints`:** Qué puerto nombrado debe rasparse (debe coincidir con el campo `name: http` del `Service`) y la ruta HTTP (en nuestro caso, `/metrics`).
3. Cuando el operador detecta una coincidencia exacta de etiquetas, reconfigura dinámicamente el archivo de configuración interno de Prometheus (`prometheus.yml`) mediante llamadas en caliente (*hot-reload*), añadiendo el target a la lista de scraping sin necesidad de reiniciar Prometheus.

### 4. ¿Cuál es la diferencia entre liveness probe y readiness probe?

| Característica | Readiness Probe (Sonda de Preparación) | Liveness Probe (Sonda de Vida) |
| :--- | :--- | :--- |
| **Pregunta que responde** | *¿El contenedor está listo para recibir tráfico de usuarios?* | *¿El contenedor sigue vivo y en un estado operativo saludable?* |
| **Acción ante fallo** | Kubernetes **remueve temporalmente la IP del pod de los endpoints del `Service`**. El pod sigue corriendo, pero no recibe tráfico. | Kubernetes **mata el contenedor y lo reinicia** siguiendo la política `restartPolicy`. |
| **Caso de uso típico** | Esperar que la aplicación Spring Boot cargue el contexto de Spring, cree cachés en memoria o caliente conexiones. | Detectar bloqueos mutuos (*deadlocks*), fugas de memoria o fallos internos irreversibles. |
| **Consecuencia de falla constante** | El pod permanece en estado `Running` pero con `READY 0/1`. | El pod acumula reinicios (*restarts*) y entra en estado de retroceso exponencial: `CrashLoopBackOff`. |

### 5. ¿Por qué es necesario apuntar Docker al daemon de Minikube antes de construir las imágenes?
Minikube corre dentro de una máquina virtual o un contenedor Docker aislado, el cual posee su propio **Docker daemon** interno y su propio almacenamiento de imágenes local, independiente del Docker de la máquina anfitriona (*host*).

Si ejecutamos `docker build` en la terminal sin configurar el entorno:
- La imagen se almacena en el Docker host de tu PC, no dentro del nodo de Minikube.
- Cuando el manifiesto del Deployment en Kubernetes intente levantar el pod con `imagePullPolicy: IfNotPresent` o `Never`, el kubelet de Minikube buscará la imagen dentro de su propio daemon local; al no encontrarla, intentará descargarla de Docker Hub público y fallará inmediatamente con el error `ErrImagePull` o `ImagePullBackOff`.

Al ejecutar el comando:
```powershell
& minikube -p minikube docker-env --shell powershell | Invoke-Expression
```
Las variables de entorno `DOCKER_HOST`, `DOCKER_TLS_VERIFY` y `DOCKER_CERT_PATH` reorientan el cliente Docker CLI del host para que los comandos `docker build` y `docker images` se ejecuten directamente dentro del cluster de Minikube, permitiendo que Kubernetes acceda a las imágenes al instante.

### 6. ¿Qué ocurre si no configuras el selector de ServiceMonitors en el values.yaml del chart?
Por defecto, el Helm chart `kube-prometheus-stack` viene configurado con la siguiente directiva restrictiva:
```yaml
serviceMonitorSelectorNilUsesHelmValues: true
```
Bajo esta configuración predeterminada:
- Prometheus **únicamente** rastrea e incluye aquellos `ServiceMonitors` que hayan sido creados por el propio release de Helm y que lleven sus etiquetas internas específicas (`release: prometheus-stack`).
- Si creas un `ServiceMonitor` propio en tu namespace de aplicación (`telemed-ns`), **Prometheus lo ignorará por completo**.
- Como consecuencia, en la página de Prometheus `http://localhost:9090/targets`, tus microservicios **nunca aparecerán**, permaneciendo invisibles para el sistema de monitoreo.

**Solución aplicada en nuestro `k8s/monitoring-values.yaml`:**
```yaml
prometheus:
  prometheusSpec:
    serviceMonitorSelectorNilUsesHelmValues: false
    serviceMonitorSelector: {}
    ruleSelectorNilUsesHelmValues: false
    ruleSelector: {}
```
Al definir `serviceMonitorSelectorNilUsesHelmValues: false` con un selector vacío `{}`, le indicamos explícitamente al Prometheus Operator que **descubra todos los ServiceMonitors y PrometheusRules existentes en cualquier namespace del cluster**.

## PARTE B — Consultas PromQL de Nuestro Sistema

### 1. Tasa de requests por minuto de tu Servicio B en los últimos 5 minutos
```promql
sum(rate(http_server_requests_seconds_count{app="dispensary-service"}[5m])) * 60
```
*Explicación:* `rate(...[5m])` calcula el promedio de peticiones por segundo en una ventana de 5 minutos; al multiplicarlo por 60 obtenemos la tasa de transacciones por minuto (*RPM*).

### 2. Latencia P95 del endpoint más crítico de tu sistema
El endpoint más crítico es la dispensación farmacéutica inter-servicio (`POST /api/dispensations`):
```promql
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{app="dispensary-service", uri="/api/dispensations"}[5m])) by (le))
```
*Explicación:* Calcula el percentil 95 (el 95% de los pacientes experimentan una latencia igual o menor a este valor en segundos).

### 3. Estado UP/DOWN de ambos servicios al mismo tiempo
```promql
up{namespace="telemed-ns"}
```
*Explicación:* Retorna una serie temporal para cada microservicio (`recipe-service` y `dispensary-service`) donde el valor `1` indica estado UP (saludable) y `0` indica estado DOWN.

### 4. Query que responde una pregunta de negocio propia de tu dominio
**Pregunta:** *¿Cuál es el porcentaje de rechazo de medicamentos por falta de stock frente al total de solicitudes procesadas en los últimos 15 minutos?*
```promql
(sum(increase(dispensary_orders_total{status="REJECTED_STOCK"}[15m])) / sum(increase(dispensary_orders_total[15m]))) * 100
```
*Explicación:* Mide la tasa de desabastecimiento farmacéutico, permitiendo a la dirección de operaciones saber si hay problemas críticos en la cadena de suministros médicos.

## PARTE C — Evidencias de Casuísticas

### 🔴 Casuística 1 — El Servicio Caído (Simulación de Fallo)
- **Contexto:** Simulación de fallo en el health check de `recipe-service` alterando las variables de entorno del Deployment.
- **Comando de Activación:**
  ```powershell
  kubectl set env deployment/recipe-deployment -n telemed-ns SIMULATE_FAILURE=true
  ```
- **Evidencia Visual:** El panel *"1. Estado de Salud"* en Grafana cambia a **DOWN** (rojo) y la regla `RecipeServiceDown` en Prometheus pasa a estado **FIRING**.
- **Comando de Resolución:**
  ```powershell
  kubectl set env deployment/recipe-deployment -n telemed-ns SIMULATE_FAILURE=false
  ```
- **Lección aprendida:** La monitorización mediante Prometheus detecta la falta de scraping antes de que los usuarios noten la degradación del servicio, disparando alertas de criticidad alta.

### 🟡 Casuística 2 — El Servicio Lento (Inyección de Latencia)
- **Contexto:** Se inyecta un retardo artificial de 3000 ms en `recipe-service`, afectando la velocidad de procesamiento de las solicitudes.
- **Comando de Activación:**
  ```powershell
  kubectl set env deployment/recipe-deployment -n telemed-ns DELAY_MS=3000
  .\load-testing\stress.ps1 -MinikubeIp "localhost"
  ```
- **Evidencia Visual:** El panel *"2. Delay Artificial (Gauge)"* en Grafana sube a `3 s` y se registra el incremento del tiempo de respuesta.
- **Comando de Resolución:**
  ```powershell
  kubectl set env deployment/recipe-deployment -n telemed-ns DELAY_MS=0
  ```
- **Lección aprendida:** Las métricas de tipo Gauge permiten visibilizar configuraciones anómalas o demoras degradantes que no necesariamente tumban el pod pero afectan la experiencia de usuario.

### 🟠 Casuística 3 — Agotamiento de Stock Farmacéutico
- **Contexto:** La ejecución continua de solicitudes de dispensación consume la totalidad de las existencias de `MED-IBU-400`.
- **Comando de Ejecución:**
  ```powershell
  .\load-testing\stress.ps1 -MinikubeIp "localhost"
  ```
- **Evidencia Visual:** Tras dispensar 10 unidades de Ibuprofeno, el panel *"7. Niveles de Stock"* cae a `0` y las solicitudes subsiguientes reciben respuestas con código HTTP `422` (Respuesta Controlada por falta de stock).
- **Lección aprendida:** La observabilidad técnica se complementa con métricas de negocio para prevenir desabastecimientos en la farmacia digital.

## PARTE D – Evidencias de Ejecución y Monitoreo

### D.2 – Dashboard de Grafana en Funcionamiento
Métricas en tiempo real tras la ejecución del script de pruebas `stress.ps1`, mostrando la tasa de peticiones HTTP, el pico de dispensaciones y la reducción del stock de Ibuprofeno a `0`:

![Dashboard Grafana en ejecución](./images/d2-dashboard.png)

### D.3 – Simulación e Impacto de Latencia (Casuística 2)
Panel de Grafana registrando la inyección de latencia artificial (`DELAY_MS=3000`) en el microservicio `recipe-service`:

![Impacto de Latencia Inyectada](./images/d3-latencia.png)

### D.4 – Alerta Disparada en Prometheus (Casuística 1)
Vista detallada en Prometheus (`http://localhost:9090/alerts`) mostrando la regla `RecipeServiceDown` activada en estado **FIRING** (`up{job="recipe-service"} == 0`):

![Alerta RecipeServiceDown en estado FIRING](./images/d4-alerta.png)