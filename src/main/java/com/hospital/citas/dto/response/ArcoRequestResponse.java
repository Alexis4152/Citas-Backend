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
}
