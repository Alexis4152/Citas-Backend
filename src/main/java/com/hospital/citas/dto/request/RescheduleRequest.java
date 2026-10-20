package com.hospital.citas.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class RescheduleRequest {

    @NotNull(message = "La fecha es obligatoria")
    @jakarta.validation.constraints.FutureOrPresent(message = "La fecha no puede ser pasada")
    private LocalDate appointmentDate;

    @NotNull(message = "La hora es obligatoria")
    private LocalTime startTime;

    private Long branchId;

    /** Apartado temporal del horario (ver SlotHold), si quien agenda lo apartó al elegirlo. */
    private java.util.UUID holdToken;

    /** Pestaña del navegador que apartó el horario (ver SlotHoldRequest#holderKey). */
    @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9-]{8,64}$", message = "Identificador de sesión de agendado inválido")
    private String holderKey;
}
