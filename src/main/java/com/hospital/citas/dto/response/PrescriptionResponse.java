package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PrescriptionResponse {
    private Long id;
    private Long appointmentId;
    private String diagnosis;
    private String content;
    private String doctorName;
    private String specialtyName;
    private String patientName;
    private LocalDate appointmentDate;
    private LocalDateTime createdAt;
    /** true si la receta fue anulada (sigue en el historial, pero ya no es vigente). */
    private boolean voided;
    private LocalDateTime voidedAt;
    private String voidReason;
}
