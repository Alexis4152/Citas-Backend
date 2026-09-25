package com.hospital.citas.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Data
public class DoctorScheduleRequest {

    @NotNull(message = "La sede es obligatoria")
    private Long branchId;

    @NotNull(message = "El día de la semana es obligatorio")
    private DayOfWeek dayOfWeek;

    @NotNull(message = "La hora de inicio es obligatoria")
    private LocalTime startTime;

    @NotNull(message = "La hora de fin es obligatoria")
    private LocalTime endTime;

    /** Si es null, se usa defaultSlotMinutes del doctor. Mínimo 5: un valor 0 o negativo haría
     * que el cálculo de espacios nunca avanzara (ciclo infinito en la disponibilidad pública). */
    @Min(value = 5, message = "La duración del espacio debe ser de al menos 5 minutos")
    @Max(value = 240, message = "La duración del espacio no puede superar 240 minutos")
    private Integer slotMinutes;
}
