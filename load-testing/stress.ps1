# ==============================================================================
# Script Parametrizable de Carga para Windows PowerShell: stress.ps1
# ==============================================================================
param (
    [string]$MinikubeIp = "",
    [int]$TotalRequests = 50,
    [double]$SleepSeconds = 0.3
)

if ([string]::IsNullOrWhiteSpace($MinikubeIp)) {
    $MinikubeIp = (minikube ip 2>$null)
    if (-not $MinikubeIp) { $MinikubeIp = "localhost" }
    $MinikubeIp = $MinikubeIp.Trim()
}

$recipeUrl = "http://${MinikubeIp}:30081"
$dispensaryUrl = "http://${MinikubeIp}:30082"

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  GENERADOR DE CARGA - TELEMEDICINA Y FARMACIA DIGITAL" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  Recipe Service:     $recipeUrl"
Write-Host "  Dispensary Service: $dispensaryUrl"
Write-Host "  Total Peticiones:   $TotalRequests"
Write-Host "======================================================================"

$meds = @("MED-AMX-500", "MED-PAR-500", "MED-IBU-400")
$doctors = @("Dra. Sofia Alarcon", "Dr. Roberto Chang", "Dra. Carmen Mendoza")

$success = 0
$fallback = 0
$rejected = 0

for ($i = 1; $i -le $TotalRequests; $i++) {
    $recId = "REC-" + (1000 + $i).ToString("0000")
    $randMed = $meds[(Get-Random -Maximum $meds.Count)]
    $randDoc = $doctors[(Get-Random -Maximum $doctors.Count)]
    $qty = (Get-Random -Minimum 1 -Maximum 6)

    Write-Host "[$i/$TotalRequests] Receta $recId ($randMed)... " -NoNewline

    # 1. Crear receta en Servicio A
    $recPayload = @{
        id = $recId
        patientName = "Paciente Test $i"
        patientDni = "708090$i"
        doctorName = $randDoc
        doctorCmp = "CMP-12345"
        medicationCode = $randMed
        medicationName = $randMed
        quantity = $qty
        instructions = "Tomar segun indicacion medica"
    } | ConvertTo-Json

    try {
        Invoke-RestMethod -Uri "$recipeUrl/api/recipes" -Method Post -Body $recPayload -ContentType "application/json" -TimeoutSec 3 | Out-Null
    } catch {
        # continuar si hay error temporal
    }

    # 2. Solicitar dispensacion en Servicio B
    $dispPayload = @{
        recipeId = $recId
        patientDni = "708090$i"
    } | ConvertTo-Json

    try {
        $resp = Invoke-RestMethod -Uri "$dispensaryUrl/api/dispensations" -Method Post -Body $dispPayload -ContentType "application/json" -TimeoutSec 6
        Write-Host "DISPENSADA (201)" -ForegroundColor Green
        $success++
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode -eq 504) {
            Write-Host "TIMEOUT / FALLBACK (504)" -ForegroundColor Red
            $fallback++
        } else {
            Write-Host "RESPUESTA CONTROLADA ($statusCode)" -ForegroundColor Yellow
            $rejected++
        }
    }

    Start-Sleep -Seconds $SleepSeconds
}

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  RESUMEN: Exito: $success | Fallbacks: $fallback | Rechazos: $rejected" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
