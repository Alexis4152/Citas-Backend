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
    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.NAME_REGEXP, message = com.hospital.citas.util.ValidationPatterns.NAME_MESSAGE)
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    @Size(max = 100)
    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.NAME_REGEXP, message = com.hospital.citas.util.ValidationPatterns.NAME_MESSAGE)
    private String lastName;

    @NotBlank(message = "El correo es obligatorio")
    @Email(regexp = com.hospital.citas.util.ValidationPatterns.EMAIL_REGEXP, message = "Correo inválido")
    @Size(max = 150)
    private String email;
}
