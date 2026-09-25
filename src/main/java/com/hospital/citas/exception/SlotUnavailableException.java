package com.hospital.citas.exception;

/** El horario solicitado (doctor + fecha + hora) ya está ocupado por otra cita activa. */
public class SlotUnavailableException extends RuntimeException {
    public SlotUnavailableException(String message) {
        super(message);
    }
}
