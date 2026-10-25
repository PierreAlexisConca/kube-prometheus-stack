# ==============================================================================
# Script de Despliegue Automatizado para Windows PowerShell: setup.ps1
# Curso: Kubernetes con Prometheus & Grafana - Valle Grande
# ==============================================================================
$ErrorActionPreference = "Stop"

Write-Host "========================================================================" -ForegroundColor Cyan
Write-Host "  INICIANDO DESPLIEGUE DESATENDIDO: TELEMEDICINA EN KUBERNETES         " -ForegroundColor Cyan
Write-Host "========================================================================" -ForegroundColor Cyan

# 1. Verificar herramientas
Write-Host "`n[1/7] Verificando herramientas requeridas..." -ForegroundColor Yellow
$tools = @("docker", "minikube", "kubectl", "helm")
foreach ($t in $tools) {
    if (-not (Get-Command $t -ErrorAction SilentlyContinue)) {
        Write-Error "El comando '$t' no esta instalado en el sistema."
    }
    Write-Host "  [OK] $t detectado correctamente" -ForegroundColor Green
}

# 2. Iniciar Minikube
Write-Host "`n[2/7] Verificando estado de Minikube..." -ForegroundColor Yellow
$status = minikube status --format='{{.Host}}' 2>$null
if ($status -ne "Running") {
    Write-Host "  Iniciando Minikube con 4 CPUs y 8192 MB RAM..."
    minikube start --cpus=4 --memory=8192 --driver=docker
} else {
    Write-Host "  [OK] Minikube ya se encuentra en ejecucion." -ForegroundColor Green
}

Write-Host "  Habilitando addon metrics-server..."
minikube addons enable metrics-server

# 3. Preparar entorno para imagenes (Docker / containerd)
Write-Host "`n[3/7] Preparando entorno de imagenes para Minikube..." -ForegroundColor Yellow
$useDockerEnv = $false
try {
    # Probar si docker-env funciona en este runtime (evita error en containerd en Windows)
    $dockerEnv = minikube docker-env --shell powershell 2>$null
    if ($LASTEXITCODE -eq 0 -and $dockerEnv -and ($dockerEnv -notmatch "SSH_AGENT_START")) {
        $dockerEnv | Invoke-Expression
        $useDockerEnv = $true
        Write-Host "  [OK] Docker CLI apuntado al daemon interno de Minikube" -ForegroundColor Green
    } else {
        Write-Host "  [INFO] Runtime containerd detectado: se usara 'minikube image load'." -ForegroundColor Yellow
    }
} catch {
    Write-Host "  [INFO] Usando docker build del host + minikube image load" -ForegroundColor Yellow
}

# 4. Construir imagenes
$rootDir = (Get-Item $PSScriptRoot).Parent.FullName
Write-Host "`n[4/7] Construyendo imagenes Docker multi-stage..." -ForegroundColor Yellow

Write-Host "  Construyendo recipe-service:1.0.0..."
docker build -t recipe-service:1.0.0 "$rootDir\microservices\recipe-service"

Write-Host "  Construyendo dispensary-service:1.0.0..."
docker build -t dispensary-service:1.0.0 "$rootDir\microservices\dispensary-service"

if (-not $useDockerEnv) {
    Write-Host "  Cargando imagenes en el cluster Minikube..." -ForegroundColor Yellow
    minikube image load recipe-service:1.0.0
    minikube image load dispensary-service:1.0.0
    Write-Host "  [OK] Imagenes cargadas exitosamente en Minikube" -ForegroundColor Green
}

# 5. Instalar o Actualizar Helm Chart de forma idempotente
Write-Host "`n[5/7] Instalando o actualizando kube-prometheus-stack con Helm..." -ForegroundColor Yellow
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

Write-Host "  Desplegando prometheus-stack en namespace monitoring..."
helm upgrade --install prometheus-stack prometheus-community/kube-prometheus-stack `
    --namespace monitoring `
    --create-namespace `
    --values "$rootDir\k8s\monitoring-values.yaml"


# 6. Aplicar manifiestos K8s
Write-Host "`n[6/7] Aplicando manifiestos de la aplicacion..." -ForegroundColor Yellow
kubectl apply -f "$rootDir\k8s\namespace.yaml"
kubectl apply -f "$rootDir\k8s\recipe-service.yaml"
kubectl apply -f "$rootDir\k8s\dispensary-service.yaml"
kubectl apply -f "$rootDir\k8s\servicemonitors.yaml"
kubectl apply -f "$rootDir\k8s\prometheus-rules.yaml"

Write-Host "  Esperando disponibilidad de pods..."
kubectl rollout status deployment/recipe-deployment -n telemed-ns --timeout=180s
kubectl rollout status deployment/dispensary-deployment -n telemed-ns --timeout=180s

# 7. Resumen de URLs
$minikubeIp = (minikube ip).Trim()
Write-Host "`n========================================================================" -ForegroundColor Green
Write-Host "  DESPLIEGUE COMPLETADO CON EXITO! URLs DEL SISTEMA:                   " -ForegroundColor Green
Write-Host "========================================================================" -ForegroundColor Green
Write-Host "  - Grafana:           http://${minikubeIp}:30000 (admin / AdminValleGrande2026!)"
Write-Host "  - Prometheus Web:    http://${minikubeIp}:30090"
Write-Host "  - Targets:           http://${minikubeIp}:30090/targets"
Write-Host "  - Recipe Service:    http://${minikubeIp}:30081"
Write-Host "  - Dispensary Service:http://${minikubeIp}:30082"
Write-Host "========================================================================" -ForegroundColor Green
