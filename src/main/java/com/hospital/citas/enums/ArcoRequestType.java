package com.hospital.citas.enums;

/**
 * Derecho ARCO que se ejerce (Ley Federal de Protección de Datos Personales en Posesión de los
 * Particulares). "Cancelación" es lo que en el día a día se dice "borrar mis datos"; se llama
 * así para que coincida con el término legal del aviso de privacidad.
 */
public enum ArcoRequestType {
    /** Saber qué datos personales tiene el hospital y para qué los usa. */
    ACCESS,
    /** Corregir datos inexactos o incompletos. */
    RECTIFICATION,
    /** Eliminar los datos, cuando proceda (el expediente clínico tiene plazos legales de guarda). */
    CANCELLATION,
    /** Oponerse a un uso específico de los datos. */
    OPPOSITION;

    public String label() {
        return switch (this) {
            case ACCESS -> "Acceso";
            case RECTIFICATION -> "Rectificación";
            case CANCELLATION -> "Cancelación";
            case OPPOSITION -> "Oposición";
        };
    }
}
