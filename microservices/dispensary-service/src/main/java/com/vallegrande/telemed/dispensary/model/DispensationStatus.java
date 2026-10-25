package com.vallegrande.telemed.dispensary.model;

public enum DispensationStatus {
    COMPLETED,
    REJECTED_STOCK,
    REJECTED_ALREADY_DISPENSED,
    REJECTED_INVALID_RECIPE,
    TIMEOUT_FALLBACK
}
