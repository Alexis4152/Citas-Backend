package com.hospital.citas.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class DoctorScheduleExceptionRequest {

    @NotNull(message = "La fecha es obligatoria")
    private LocalDate date;

    private boolean allDay = true;

    private LocalTime startTime;

    private LocalTime endTime;

    private String reason;
}
