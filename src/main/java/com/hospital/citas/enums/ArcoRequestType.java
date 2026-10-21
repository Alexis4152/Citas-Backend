package com.hospital.citas.enums;

/**
 * Derecho que se ejerce (Ley Federal de Protección de Datos Personales en Posesión de los
 * Particulares). "Cancelación" es lo que en el día a día se dice "borrar mis datos" (o
 * "eliminar mi cuenta"); se llama así para que coincida con el término legal del aviso.
 */
public enum ArcoRequestType {
    /** Saber qué datos personales tiene el hospital y para qué los usa (se entrega un reporte PDF). */
    ACCESS,
    /** Corregir datos inexactos o incompletos. */
    RECTIFICATION,
    /** Eliminar los datos / la cuenta: lo que la ley obliga a guardar se bloquea hasta que vence. */
    CANCELLATION,
    /** Oponerse a un uso específico de los datos. */
    OPPOSITION,
    /** Retirar el consentimiento que se dio para tratar los datos. */
    REVOCATION,
    /** Limitar el uso o la divulgación (por ejemplo, no recibir recordatorios o novedades). */
    LIMITATION;

    public String label() {
        return switch (this) {
            case ACCESS -> "Acceso";
            case RECTIFICATION -> "Rectificación";
            case CANCELLATION -> "Cancelación";
            case OPPOSITION -> "Oposición";
            case REVOCATION -> "Revocación del consentimiento";
            case LIMITATION -> "Limitación de uso o divulgación";
        };
    }

    /** Derechos que exponen o eliminan datos: necesitan identidad verificada para proceder. */
    public boolean needsStrongIdentity() {
        return this == ACCESS || this == CANCELLATION || this == RECTIFICATION || this == REVOCATION;
    }
}
