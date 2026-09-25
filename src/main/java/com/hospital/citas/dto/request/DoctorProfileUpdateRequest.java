package com.hospital.citas.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DoctorProfileUpdateRequest {
    @Size(max = 2000, message = "La biografía no puede superar 2000 caracteres")
    private String bio;

    private String photoUrl;

    @Size(max = 5000, message = "La plantilla de receta no puede superar 5000 caracteres")
    private String prescriptionTemplate;
}
