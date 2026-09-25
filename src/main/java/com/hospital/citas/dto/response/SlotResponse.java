package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SlotResponse {
    private LocalTime startTime;
    private LocalTime endTime;
    private boolean available;
    /** Solo en la vista de recepción/admin: el espacio está ocupado por otra cita pero aún se
     * puede empalmar como sobrecupo (no está bloqueado por la agenda del doctor ni terminó). */
    private boolean overbookable;
}
