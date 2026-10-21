package com.hospital.citas.dto.request;

/** Cuerpos de las acciones del administrador sobre una solicitud ARCO. */
public final class ArcoActions {

    private ArcoActions() {
    }

    /** {@code method}: ID_ONLINE · ID_IN_PERSON · SESSION_PASSWORD · REPRESENTATIVE. */
    public record Verify(String method, String notes) {
    }

    /** Requerimiento de información o documentos al titular (10 días hábiles para contestar). */
    public record RequestInfo(String message) {
    }

    /** Prórroga única de 20 días hábiles, justificada. */
    public record Extend(String reason) {
    }

    /** {@code outcome}: PROCEDENTE · PARCIAL · IMPROCEDENTE. */
    public record Respond(String outcome, String legalBasis, String notes) {
    }

    /**
     * Ejecución del derecho. Según el tipo se usa una parte:
     * Rectificación → {@code profile} y/o {@code clarification}; Oposición/Limitación →
     * {@code reminders}/{@code news}; Revocación → {@code revocationScope} (SECONDARY o ALL).
     */
    public record Execute(PatientProfileRequest profile, String clarification, Boolean reminders, Boolean news,
                          String revocationScope, String notes) {
    }

    /** Solicitud registrada en mostrador (titular sin correo, menores, representantes). */
    public record InPerson(Long patientId, String type, String details, String contactEmail,
                           Boolean representative, String representativeName, String identityNotes) {
    }

    /** Respuesta del titular a un requerimiento (desde su enlace de seguimiento). */
    public record InfoAnswer(String message) {
    }
}
