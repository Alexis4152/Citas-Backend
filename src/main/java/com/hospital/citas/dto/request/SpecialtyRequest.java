package com.hospital.citas.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SpecialtyRequest {

    @NotBlank(message = "El nombre es obligatorio")
    private String name;

    private String description;

    /** Vacío/nulo = usa el texto genérico de respaldo (ver Specialty#recommendations). */
    private String recommendations;
}
