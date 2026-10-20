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

    @jakarta.validation.constraints.Size(max = 255, message = "El motivo no puede superar 255 caracteres")
    private String reason;

    @jakarta.validation.constraints.AssertTrue(message = "Si la ausencia no es de todo el día, indica hora de inicio y de fin")
    public boolean isPartialRangePresent() {
        return allDay || (startTime != null && endTime != null);
    }

    @jakarta.validation.constraints.AssertTrue(message = "La hora de fin de la ausencia debe ser posterior a la de inicio")
    public boolean isPartialRangeValid() {
        return allDay || startTime == null || endTime == null || endTime.isAfter(startTime);
    }

    @jakarta.validation.constraints.AssertTrue(message = "No se puede registrar una ausencia en una fecha que ya pasó")
    public boolean isDateNotPast() {
        return date == null || !date.isBefore(java.time.LocalDate.now());
    }
}
