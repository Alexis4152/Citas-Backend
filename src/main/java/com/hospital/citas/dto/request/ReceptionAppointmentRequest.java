package com.hospital.citas.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** Agendado hecho por recepción/admin a nombre de un paciente ya existente (por teléfono). */
@Data
public class ReceptionAppointmentRequest {

    @NotNull(message = "El paciente es obligatorio")
    private Long patientId;

    @NotNull(message = "El doctor es obligatorio")
    private Long doctorId;

    @NotNull(message = "La sede es obligatoria")
    private Long branchId;

    @NotNull(message = "La fecha es obligatoria")
    private LocalDate appointmentDate;

    @NotNull(message = "La hora es obligatoria")
    private LocalTime startTime;

    private String reasonForVisit;

    /** Sobrecupo: si el horario ya está ocupado por otra cita, empalma esta como extra en vez
     * de rechazarla (urgencias, paciente que llega sin cita). Sin efecto si el horario está libre. */
    private Boolean overbook;
}
