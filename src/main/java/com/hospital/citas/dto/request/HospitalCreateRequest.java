package com.hospital.citas.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Alta de un hospital/consultorio por el SUPER_ADMIN, junto con su primer administrador. */
@Data
public class HospitalCreateRequest {

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 150)
    private String name;

    @NotBlank(message = "El identificador del enlace es obligatorio")
    @Size(min = 3, max = 60, message = "El identificador debe tener entre 3 y 60 caracteres")
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
            message = "El identificador solo admite minúsculas, números y guiones (ej. clinica-san-jose)")
    private String slug;

    @NotBlank(message = "El nombre del administrador es obligatorio")
    @Size(max = 100)
    private String adminFirstName;

    @NotBlank(message = "El apellido del administrador es obligatorio")
    @Size(max = 100)
    private String adminLastName;

    @NotBlank(message = "El correo del administrador es obligatorio")
    @Email(message = "Correo inválido")
    @Size(max = 150)
    private String adminEmail;
}
