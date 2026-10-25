#!/usr/bin/env bash
# ==============================================================================
# Script de Desinstalacion y Limpieza: teardown.sh
# Uso:
#   ./scripts/teardown.sh        -> Elimina recursos y deployments
#   ./scripts/teardown.sh --all  -> Elimina tambien el cluster Minikube completo
# ==============================================================================
set -e

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo -e "${YELLOW}Eliminando recursos de la aplicacion en telemed-ns...${NC}"
kubectl delete -f "$ROOT_DIR/k8s/prometheus-rules.yaml" --ignore-not-found=true
kubectl delete -f "$ROOT_DIR/k8s/servicemonitors.yaml" --ignore-not-found=true
kubectl delete -f "$ROOT_DIR/k8s/dispensary-service.yaml" --ignore-not-found=true
kubectl delete -f "$ROOT_DIR/k8s/recipe-service.yaml" --ignore-not-found=true
kubectl delete -f "$ROOT_DIR/k8s/namespace.yaml" --ignore-not-found=true

echo -e "${YELLOW}Desinstalando Helm release de monitoreo...${NC}"
helm uninstall prometheus-stack -n monitoring 2>/dev/null || true
kubectl delete namespace monitoring --ignore-not-found=true

if [ "$1" == "--all" ]; then
    echo -e "${RED}Eliminando el cluster Minikube completo (--all detectado)...${NC}"
    minikube delete
    echo -e "${GREEN}✓ Cluster Minikube eliminado.${NC}"
else
    echo -e "${GREEN}✓ Recursos eliminados. El cluster Minikube permanece activo.${NC}"
    echo -e "  (Usa './scripts/teardown.sh --all' si deseas destruir Minikube por completo)."
fi
