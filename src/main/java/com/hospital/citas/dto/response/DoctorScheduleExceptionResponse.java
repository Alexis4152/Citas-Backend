package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DoctorScheduleExceptionResponse {
    private Long id;
    private LocalDate date;
    private boolean allDay;
    private LocalTime startTime;
    private LocalTime endTime;
    private String reason;
    /** Solo al crear: citas programadas que caen en el bloqueo y hay que reprogramar. */
    private Long affectedAppointments;
}
