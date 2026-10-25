package com.vallegrande.telemed.dispensary.model;

import java.time.LocalDateTime;

public class DispensationOrder {
    private String orderId;
    private String recipeId;
    private String patientName;
    private String medicationCode;
    private String medicationName;
    private int quantity;
    private DispensationStatus status;
    private String notes;
    private LocalDateTime timestamp;

    public DispensationOrder() {
        this.timestamp = LocalDateTime.now();
    }

    public DispensationOrder(String orderId, String recipeId, String patientName,
                             String medicationCode, String medicationName, int quantity,
                             DispensationStatus status, String notes) {
        this.orderId = orderId;
        this.recipeId = recipeId;
        this.patientName = patientName;
        this.medicationCode = medicationCode;
        this.medicationName = medicationName;
        this.quantity = quantity;
        this.status = status;
        this.notes = notes;
        this.timestamp = LocalDateTime.now();
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getRecipeId() {
        return recipeId;
    }

    public void setRecipeId(String recipeId) {
        this.recipeId = recipeId;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public String getMedicationCode() {
        return medicationCode;
    }

    public void setMedicationCode(String medicationCode) {
        this.medicationCode = medicationCode;
    }

    public String getMedicationName() {
        return medicationName;
    }

    public void setMedicationName(String medicationName) {
        this.medicationName = medicationName;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public DispensationStatus getStatus() {
        return status;
    }

    public void setStatus(DispensationStatus status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
