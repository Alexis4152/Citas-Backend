package com.hospital.citas.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** Apartar un horario mientras se termina de agendar (público, sin sesión). */
@Data
public class SlotHoldRequest {

    @NotNull(message = "El doctor es obligatorio")
    private Long doctorId;

    @NotNull(message = "La sede es obligatoria")
    private Long branchId;

    @NotNull(message = "La fecha es obligatoria")
    @FutureOrPresent(message = "La fecha no puede ser pasada")
    private LocalDate date;

    @NotNull(message = "La hora es obligatoria")
    private LocalTime startTime;

    /** Aleatorio que genera la pestaña del navegador (ver SlotHold#holderKey). */
    @NotBlank(message = "Falta el identificador de la sesión de agendado")
    @Pattern(regexp = "^[A-Za-z0-9-]{8,64}$", message = "Identificador de sesión de agendado inválido")
    private String holderKey;
}
