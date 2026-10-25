package com.vallegrande.telemed.recipe.model;

import java.time.LocalDateTime;

public class MedicalRecipe {
    private String id;
    private String patientName;
    private String patientDni;
    private String doctorName;
    private String doctorCmp;
    private String medicationCode;
    private String medicationName;
    private int quantity;
    private String instructions;
    private RecipeStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime dispensedAt;

    public MedicalRecipe() {
        this.status = RecipeStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    public MedicalRecipe(String id, String patientName, String patientDni, String doctorName,
                         String doctorCmp, String medicationCode, String medicationName,
                         int quantity, String instructions) {
        this.id = id;
        this.patientName = patientName;
        this.patientDni = patientDni;
        this.doctorName = doctorName;
        this.doctorCmp = doctorCmp;
        this.medicationCode = medicationCode;
        this.medicationName = medicationName;
        this.quantity = quantity;
        this.instructions = instructions;
        this.status = RecipeStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public String getPatientDni() {
        return patientDni;
    }

    public void setPatientDni(String patientDni) {
        this.patientDni = patientDni;
    }

    public String getDoctorName() {
        return doctorName;
    }

    public void setDoctorName(String doctorName) {
        this.doctorName = doctorName;
    }

    public String getDoctorCmp() {
        return doctorCmp;
    }

    public void setDoctorCmp(String doctorCmp) {
        this.doctorCmp = doctorCmp;
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

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public RecipeStatus getStatus() {
        return status;
    }

    public void setStatus(RecipeStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getDispensedAt() {
        return dispensedAt;
    }

    public void setDispensedAt(LocalDateTime dispensedAt) {
        this.dispensedAt = dispensedAt;
    }
}
