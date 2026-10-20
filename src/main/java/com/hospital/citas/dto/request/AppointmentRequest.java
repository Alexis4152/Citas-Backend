package com.hospital.citas.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** Agendado por un paciente autenticado, para sí mismo. */
@Data
public class AppointmentRequest {

    @NotNull(message = "El doctor es obligatorio")
    private Long doctorId;

    @NotNull(message = "La sede es obligatoria")
    private Long branchId;

    @NotNull(message = "La fecha es obligatoria")
    @jakarta.validation.constraints.FutureOrPresent(message = "La fecha de la cita no puede ser pasada")
    private LocalDate appointmentDate;

    @NotNull(message = "La hora es obligatoria")
    private LocalTime startTime;

    @jakarta.validation.constraints.Size(max = 1000, message = "El motivo no puede superar 1000 caracteres")
    private String reasonForVisit;

    /** Aunque ya sea un paciente con cuenta (podría tener esto capturado de una cita
     * anterior), se vuelve a pedir/actualizar en cada agendado -- así se mantiene al día si
     * cambió algo (nueva alergia, etc.), en vez de congelar lo que se capturó la primera vez. */
    @NotNull(message = "Alergias y tipo de sangre son obligatorios")
    @Valid
    private MedicalInfoRequest medicalInfo;

    /** Apartado temporal del horario (ver SlotHold), si quien agenda lo apartó al elegirlo. */
    private java.util.UUID holdToken;

    /** Pestaña del navegador que apartó el horario (ver SlotHoldRequest#holderKey). */
    @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9-]{8,64}$", message = "Identificador de sesión de agendado inválido")
    private String holderKey;
}
