package com.hospital.citas.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VoidPrescriptionRequest {
    @NotBlank(message = "El motivo de la anulación es obligatorio")
    @Size(max = 500, message = "El motivo no puede superar 500 caracteres")
    private String reason;
}
