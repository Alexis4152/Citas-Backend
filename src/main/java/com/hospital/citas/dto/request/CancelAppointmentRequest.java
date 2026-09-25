package com.hospital.citas.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CancelAppointmentRequest {
    @Size(max = 500, message = "El motivo no puede superar 500 caracteres")
    private String reason;
}
