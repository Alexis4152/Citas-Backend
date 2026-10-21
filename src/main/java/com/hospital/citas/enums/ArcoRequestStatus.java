package com.hospital.citas.enums;

/**
 * Etapas de una solicitud ARCO, en orden:
 * PENDING_EMAIL → PENDING → (INFO_REQUESTED → PENDING) → APPROVED → COMPLETED, o REJECTED /
 * NOT_FILED. Los plazos legales corren desde que se confirma el correo (PENDING).
 */
public enum ArcoRequestStatus {
    /** Formulario público enviado, falta que quien la pidió confirme su correo. */
    PENDING_EMAIL,
    /** Recibida: corre el plazo de 20 días hábiles para responder. */
    PENDING,
    /** Se pidió información o documentos al titular (tiene 10 días hábiles). */
    INFO_REQUESTED,
    /** Respondida como procedente: corre el plazo de 15 días hábiles para hacerla efectiva. */
    APPROVED,
    /** Atendida y ejecutada (o respondida como procedente sin nada que ejecutar). */
    COMPLETED,
    /** Respondida como improcedente, con su fundamento. */
    REJECTED,
    /** El titular no completó lo que se le pidió a tiempo: se tiene por no presentada. */
    NOT_FILED;

    public String label() {
        return switch (this) {
            case PENDING_EMAIL -> "Por confirmar correo";
            case PENDING -> "Recibida";
            case INFO_REQUESTED -> "Información solicitada";
            case APPROVED -> "Procedente, por ejecutar";
            case COMPLETED -> "Atendida";
            case REJECTED -> "Improcedente";
            case NOT_FILED -> "No presentada";
        };
    }

    public boolean isOpen() {
        return this == PENDING || this == INFO_REQUESTED || this == APPROVED;
    }
}
