# ==============================================================================
# Script de Desinstalacion para PowerShell: teardown.ps1
# ==============================================================================
param (
    [switch]$All = $false
)

$rootDir = (Get-Item $PSScriptRoot).Parent.FullName

Write-Host "Eliminando recursos de telemed-ns..." -ForegroundColor Yellow
kubectl delete -f "$rootDir\k8s\prometheus-rules.yaml" --ignore-not-found=true
kubectl delete -f "$rootDir\k8s\servicemonitors.yaml" --ignore-not-found=true
kubectl delete -f "$rootDir\k8s\dispensary-service.yaml" --ignore-not-found=true
kubectl delete -f "$rootDir\k8s\recipe-service.yaml" --ignore-not-found=true
kubectl delete -f "$rootDir\k8s\namespace.yaml" --ignore-not-found=true

Write-Host "Desinstalando release de Helm en namespace monitoring..." -ForegroundColor Yellow
helm uninstall prometheus-stack -n monitoring 2>$null
kubectl delete namespace monitoring --ignore-not-found=true

if ($All) {
    Write-Host "Eliminando el cluster Minikube completo..." -ForegroundColor Red
    minikube delete
    Write-Host "[OK] Cluster Minikube eliminado." -ForegroundColor Green
} else {
    Write-Host "[OK] Recursos eliminados. El cluster Minikube sigue activo." -ForegroundColor Green
}
