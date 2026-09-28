package com.hospital.citas.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Administrador adicional de un hospital, dado de alta por el SUPER_ADMIN. */
@Data
public class HospitalAdminRequest {

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100)
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    @Size(max = 100)
    private String lastName;

    @NotBlank(message = "El correo es obligatorio")
    @Email(message = "Correo inválido")
    @Size(max = 150)
    private String email;
}
