package com.hospital.citas.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SpecialtyRequest {

    @NotBlank(message = "El nombre es obligatorio")
    @jakarta.validation.constraints.Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    private String name;

    @jakarta.validation.constraints.Size(max = 1000, message = "La descripción no puede superar 1000 caracteres")
    private String description;

    /** Vacío/nulo = usa el texto genérico de respaldo (ver Specialty#recommendations). */
    @jakarta.validation.constraints.Size(max = 2000, message = "Las recomendaciones no pueden superar 2000 caracteres")
    private String recommendations;
}
