#!/usr/bin/env bash
# ==============================================================================
# Script de Despliegue Automatizado y Desatendido: setup.sh
# Curso: Kubernetes con Prometheus & Grafana - Valle Grande
# Dominio: Telemedicina & Farmacia Digital
# ==============================================================================
set -e

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}========================================================================${NC}"
echo -e "${BLUE}  INICIANDO DESPLIEGUE DESATENDIDO: TELEMEDICINA EN KUBERNETES         ${NC}"
echo -e "${BLUE}========================================================================${NC}"

# Paso 1: Verificar prerequisitos
echo -e "\n${YELLOW}[1/7] Verificando herramientas necesarias...${NC}"
for cmd in docker minikube kubectl helm; do
    if ! command -v $cmd &> /dev/null; then
        echo -e "${RED}[ERROR] El comando '$cmd' no esta instalado en el sistema. Abortando.${NC}"
        exit 1
    fi
    echo -e "${GREEN}  [OK] $cmd detectado: $(command -v $cmd)${NC}"
done

# Paso 2: Verificar o arrancar Minikube con recursos suficientes
echo -e "\n${YELLOW}[2/7] Verificando estado del cluster Minikube...${NC}"
MINIKUBE_STATUS=$(minikube status --format='{{.Host}}' 2>/dev/null || echo "Stopped")
if [ "$MINIKUBE_STATUS" != "Running" ]; then
    echo -e "  Arrancando Minikube con 4 CPUs y 8192 MB de memoria..."
    minikube start --cpus=4 --memory=8192 --driver=docker
else
    echo -e "${GREEN}  [OK] Minikube ya se encuentra en ejecucion.${NC}"
fi

echo -e "  Habilitando addon metrics-server..."
minikube addons enable metrics-server

# Paso 3 y 4: Construir imagenes
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

USE_DOCKER_ENV=false
if eval $(minikube docker-env 2>/dev/null); then
    echo -e "${GREEN}  [OK] Docker CLI apuntado al daemon interno de Minikube.${NC}"
    USE_DOCKER_ENV=true
else
    echo -e "${YELLOW}  [INFO] Usando docker build en host + minikube image load (containerd).${NC}"
fi

echo -e "\n${YELLOW}[4/7] Construyendo imagenes Docker multi-stage...${NC}"
echo -e "  Construyendo imagen recipe-service:1.0.0..."
docker build -t recipe-service:1.0.0 "$ROOT_DIR/microservices/recipe-service"

echo -e "  Construyendo imagen dispensary-service:1.0.0..."
docker build -t dispensary-service:1.0.0 "$ROOT_DIR/microservices/dispensary-service"

if [ "$USE_DOCKER_ENV" = false ]; then
    echo -e "${YELLOW}  Cargando imagenes en Minikube...${NC}"
    minikube image load recipe-service:1.0.0
    minikube image load dispensary-service:1.0.0
    echo -e "${GREEN}  [OK] Imagenes cargadas en Minikube.${NC}"
fi

# Paso 5: Instalar o Actualizar Stack de Monitoreo con Helm (kube-prometheus-stack)
echo -e "\n${YELLOW}[5/7] Instalando o actualizando kube-prometheus-stack con Helm...${NC}"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts 2>/dev/null || true
helm repo update

echo -e "  Desplegando prometheus-stack en namespace monitoring..."
helm upgrade --install prometheus-stack prometheus-community/kube-prometheus-stack \
    --namespace monitoring \
    --create-namespace \
    --values "$ROOT_DIR/k8s/monitoring-values.yaml"

# Paso 6: Aplicar Manifiestos de Kubernetes de la Aplicacion
echo -e "\n${YELLOW}[6/7] Aplicando manifiestos de la aplicacion en telemed-ns...${NC}"
kubectl apply -f "$ROOT_DIR/k8s/namespace.yaml"
kubectl apply -f "$ROOT_DIR/k8s/recipe-service.yaml"
kubectl apply -f "$ROOT_DIR/k8s/dispensary-service.yaml"
kubectl apply -f "$ROOT_DIR/k8s/servicemonitors.yaml"
kubectl apply -f "$ROOT_DIR/k8s/prometheus-rules.yaml"

echo -e "  Esperando a que los pods de los microservicios esten en estado Running..."
kubectl rollout status deployment/recipe-deployment -n telemed-ns --timeout=180s
kubectl rollout status deployment/dispensary-deployment -n telemed-ns --timeout=180s

# Paso 7: Obtener IPs y URLs de Acceso
echo -e "\n${YELLOW}[7/7] Obteniendo URLs de acceso publicas...${NC}"
MINIKUBE_IP=$(minikube ip)

echo -e "\n${GREEN}========================================================================${NC}"
echo -e "${GREEN}  DESPLIEGUE COMPLETADO CON EXITO! URLs DEL SISTEMA:                   ${NC}"
echo -e "${GREEN}========================================================================${NC}"
echo -e "  - Grafana:           http://${MINIKUBE_IP}:30000"
echo -e "    * Usuario:         admin"
echo -e "    * Password:        AdminValleGrande2026!"
echo -e "  - Prometheus Web:    http://${MINIKUBE_IP}:30090"
echo -e "    * Targets:         http://${MINIKUBE_IP}:30090/targets"
echo -e "  - Recipe Service:    http://${MINIKUBE_IP}:30081"
echo -e "    * Health Check:    http://${MINIKUBE_IP}:30081/health"
echo -e "    * Metricas:        http://${MINIKUBE_IP}:30081/metrics"
echo -e "    * Endpoints API:   http://${MINIKUBE_IP}:30081/api/recipes"
echo -e "  - Dispensary Service:http://${MINIKUBE_IP}:30082"
echo -e "    * Health Check:    http://${MINIKUBE_IP}:30082/health"
echo -e "    * Metricas:        http://${MINIKUBE_IP}:30082/metrics"
echo -e "    * Endpoints API:   http://${MINIKUBE_IP}:30082/api/dispensations"
echo -e "${GREEN}========================================================================${NC}"
echo -e "Para importar el dashboard en Grafana, usa el archivo: k8s/grafana-dashboard.json"
