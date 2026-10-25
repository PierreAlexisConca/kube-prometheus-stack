#!/usr/bin/env bash
# ==============================================================================
# Script Parametrizable de Carga y Pruebas: stress.sh
# Uso:
#   ./load-testing/stress.sh [MINIKUBE_IP] [TOTAL_REQUESTS] [DELAY_SECONDS]
# Ejemplo:
#   ./load-testing/stress.sh 192.168.49.2 100 0.2
# ==============================================================================

MINIKUBE_IP=${1:-$(minikube ip 2>/dev/null || echo "localhost")}
TOTAL_REQUESTS=${2:-50}
SLEEP_INTERVAL=${3:-0.3}

RECIPE_URL="http://${MINIKUBE_IP}:30081"
DISPENSARY_URL="http://${MINIKUBE_IP}:30082"

echo "======================================================================"
echo "  GENERADOR DE CARGA — TELEMEDICINA & FARMACIA DIGITAL"
echo "======================================================================"
echo "  Destino Recipe Service:     $RECIPE_URL"
echo "  Destino Dispensary Service: $DISPENSARY_URL"
echo "  Peticiones Totales:         $TOTAL_REQUESTS"
echo "  Pausa entre Peticiones:     ${SLEEP_INTERVAL}s"
echo "======================================================================"

MEDS=("MED-AMX-500" "MED-PAR-500" "MED-IBU-400")
DOCTORS=("Dra. Sofia Alarcon" "Dr. Roberto Chang" "Dra. Carmen Mendoza")

success_count=0
fallback_count=0
rejected_count=0

for ((i=1; i<=TOTAL_REQUESTS; i++)); do
    REC_ID="REC-$(printf "%04d" $((1000 + i)))"
    RAND_MED=${MEDS[$RANDOM % ${#MEDS[@]}]}
    RAND_DOC=${DOCTORS[$RANDOM % ${#DOCTORS[@]}]}
    QTY=$(( (RANDOM % 5) + 1 ))

    echo -ne "[$i/$TOTAL_REQUESTS] Emitiendo receta $REC_ID ($RAND_MED)... "

    # 1. Crear Receta en Servicio A
    REC_PAYLOAD="{\"id\":\"$REC_ID\",\"patientName\":\"Paciente Test $i\",\"patientDni\":\"708090$i\",\"doctorName\":\"$RAND_DOC\",\"doctorCmp\":\"CMP-12345\",\"medicationCode\":\"$RAND_MED\",\"medicationName\":\"$RAND_MED\",\"quantity\":$QTY,\"instructions\":\"Tomar segun indicacion medica\"}"
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$RECIPE_URL/api/recipes" \
        -H "Content-Type: application/json" -d "$REC_PAYLOAD" --connect-timeout 2)

    # 2. Solicitar Dispensacion en Servicio B
    DISP_PAYLOAD="{\"recipeId\":\"$REC_ID\",\"patientDni\":\"708090$i\"}"
    DISP_RESP=$(curl -s -w "\n%{http_code}" -X POST "$DISPENSARY_URL/api/dispensations" \
        -H "Content-Type: application/json" -d "$DISP_PAYLOAD" --connect-timeout 5)

    DISP_CODE=$(echo "$DISP_RESP" | tail -n1)
    DISP_BODY=$(echo "$DISP_RESP" | sed '$d')

    if [ "$DISP_CODE" == "201" ]; then
        echo -e "\e[32mDISPENSADA (201)\e[0m"
        ((success_count++))
    elif [ "$DISP_CODE" == "504" ]; then
        echo -e "\e[31mTIMEOUT / FALLBACK (504)\e[0m"
        ((fallback_count++))
    else
        echo -e "\e[33mRESPUESTA CONTROLADA ($DISP_CODE)\e[0m"
        ((rejected_count++))
    fi

    # Consultar inventory cada 10 peticiones
    if (( i % 10 == 0 )); then
        curl -s "$DISPENSARY_URL/api/dispensations/pharmacy/inventory" > /dev/null
    fi

    sleep "$SLEEP_INTERVAL"
done

echo "======================================================================"
echo "  RESUMEN DE PRUEBA DE CARGA:"
echo "    - Completadas con Exito: $success_count"
echo "    - Timeouts / Fallbacks:  $fallback_count"
echo "    - Rechazos / Negocio:    $rejected_count"
echo "======================================================================"
