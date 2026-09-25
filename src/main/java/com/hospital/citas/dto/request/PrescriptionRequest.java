package com.hospital.citas.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PrescriptionRequest {
    @NotNull(message = "La cita es obligatoria")
    private Long appointmentId;

    @Size(max = 2000, message = "El diagnóstico no puede superar 2000 caracteres")
    private String diagnosis;

    @NotBlank(message = "El contenido de la receta es obligatorio")
    @Size(max = 5000, message = "El contenido de la receta no puede superar 5000 caracteres")
    private String content;

    /** La cita ya tiene una receta vigente y el doctor confirma que quiere emitir otra
     * (en vez de anular la anterior). Sin esta confirmación se rechaza, para evitar recetas
     * duplicadas por un doble clic o por olvidar que ya se había emitido una. */
    private Boolean confirmAdditional;
}
