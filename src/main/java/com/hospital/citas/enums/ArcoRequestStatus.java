package com.hospital.citas.enums;

/** Estado de una solicitud ARCO. */
public enum ArcoRequestStatus {
    /** Recibida, sin atender todavía. */
    PENDING,
    /** Atendida y resuelta a favor de quien la pidió. */
    COMPLETED,
    /** Atendida y rechazada (ej. no se pudo verificar la identidad). */
    REJECTED;

    public String label() {
        return switch (this) {
            case PENDING -> "Pendiente";
            case COMPLETED -> "Completada";
            case REJECTED -> "Rechazada";
        };
    }
}
