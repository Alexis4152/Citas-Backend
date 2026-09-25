package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationResponse {
    private Long id;
    private String type;
    private String message;
    private Long appointmentId;
    /** Fecha ACTUAL de la cita (no la de cuando se creó la notificación): si se volvió a
     * reprogramar después, esto ya refleja dónde vive hoy -- para que el frontend arme el
     * filtro/salto directo al módulo de Citas con esa fecha. Null si la cita ya no existe. */
    private LocalDate appointmentDate;
    private Boolean read;
    private LocalDateTime createdAt;
}
