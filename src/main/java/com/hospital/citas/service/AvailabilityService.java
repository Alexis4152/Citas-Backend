package com.hospital.citas.service;

import com.hospital.citas.dto.response.DayAvailabilityResponse;
import com.hospital.citas.entity.Branch;
import com.hospital.citas.entity.Doctor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface AvailabilityService {

    /** Disponibilidad computada para {@code days} días a partir de {@code from} (inclusive),
     * con las reglas del público (paciente/invitado): mínimo 30 minutos de anticipación. */
    List<DayAvailabilityResponse> getAvailability(Long doctorId, LocalDate from, int days);

    /** {@code staffMode} = recepción/admin: sin anticipación mínima (mientras el espacio no
     * haya terminado, sirve para pacientes que llegan sin cita) y marcando como
     * {@code overbookable} los espacios ya ocupados que aún se pueden empalmar. */
    List<DayAvailabilityResponse> getAvailability(Long doctorId, LocalDate from, int days, boolean staffMode);

    /**
     * Valida que (branch, date, startTime) caiga dentro del horario semanal del doctor en esa
     * sede y no esté bloqueado por una excepción de agenda. Devuelve la hora de fin calculada.
     * No valida ocupación (eso lo hace {@code AppointmentService} con bloqueo pesimista).
     */
    LocalTime resolveEndTimeOrThrow(Doctor doctor, Branch branch, LocalDate date, LocalTime startTime);

    /** {@code staffMode}: recepción/admin pueden agendar un espacio que ya empezó (paciente
     * que llega sin cita), siempre que el espacio no haya terminado. */
    LocalTime resolveEndTimeOrThrow(Doctor doctor, Branch branch, LocalDate date, LocalTime startTime, boolean staffMode);
}
