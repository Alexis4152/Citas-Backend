package com.hospital.citas.service;

import com.hospital.citas.entity.Appointment;

public interface AppointmentReceiptService {

    /** Arma el comprobante de la cita en PDF, en memoria (patrón 09), con un QR (patrón 11)
     * que codifica la URL pública de confirmación. Debe invocarse dentro de una transacción
     * con el {@link Appointment} ya cargado, para poder leer sus relaciones LAZY
     * (doctor/sede/paciente). */
    byte[] build(Appointment appointment);
}
