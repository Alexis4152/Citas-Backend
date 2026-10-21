package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ArcoRequestResponse {
    private Long id;
    /** Folio para que quien la pidió pueda darle seguimiento (ej. ARCO-000123). */
    private String folio;
    private String requesterName;
    private String requesterEmail;
    private String requesterPhone;
    private String type;
    private String typeLabel;
    private String status;
    private String statusLabel;
    private String details;
    private String resolutionNotes;
    private LocalDateTime createdAt;
    private LocalDate dueDate;
    /** Pendiente y ya pasó la fecha límite legal. */
    private boolean overdue;
    private LocalDateTime resolvedAt;
    private String resolvedByName;
    /** Se encontró un registro de paciente con ese correo (para Acceso: hay datos que exportar). */
    private boolean hasPatientRecord;
    private boolean hasAccount;
    /** Solo en la vista de la plataforma (SUPER_ADMIN). */
    private String hospitalName;
    private String hospitalSlug;

    private String channel;
    private LocalDateTime receivedAt;
    private boolean emailVerified;
    private boolean representative;
    private String representativeName;
    private LocalDateTime identityVerifiedAt;
    private String identityMethod;
    private String identityMethodLabel;
    private String identityVerifiedByName;
    private String identityNotes;
    /** El derecho necesita identidad verificada y todavía no la tiene. */
    private boolean identityRequired;
    private String infoRequest;
    private LocalDate infoDue;
    private String infoResponse;
    private boolean extended;
    private String extensionReason;
    private String outcome;
    private String outcomeLabel;
    private String legalBasis;
    private LocalDate executionDue;
    private LocalDateTime executedAt;
    private String executedByName;
    private String executionSummary;
    private Long patientId;
    private String patientName;
    /** Sigue abierta (recibida, con requerimiento o por ejecutar). */
    private boolean open;
    /** Próxima fecha límite que corre (respuesta, información del titular o ejecución). */
    private LocalDate nextDeadline;
    private String nextDeadlineLabel;
}
