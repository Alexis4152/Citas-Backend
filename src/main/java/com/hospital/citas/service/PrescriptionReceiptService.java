package com.hospital.citas.service;

import com.hospital.citas.entity.Prescription;

public interface PrescriptionReceiptService {

    /** Arma la receta en PDF, en memoria -- debe invocarse dentro de una transacción con la
     * {@link Prescription} y su cita ya cargadas (doctor/paciente), igual que
     * {@link AppointmentReceiptService#build}. */
    byte[] build(Prescription prescription);
}
