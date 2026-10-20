package com.hospital.citas.dto.request;

import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "El correo es obligatorio")
    @Email(regexp = com.hospital.citas.util.ValidationPatterns.EMAIL_REGEXP, message = "El correo no es válido")
    private String email;

    @NotBlank(message = "La contraseña es obligatoria")
    @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
    @Pattern(regexp = ValidationPatterns.PASSWORD_REGEXP, message = ValidationPatterns.PASSWORD_MESSAGE)
    private String password;

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.NAME_REGEXP, message = com.hospital.citas.util.ValidationPatterns.NAME_MESSAGE)
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    @Size(max = 100, message = "El apellido no puede superar 100 caracteres")
    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.NAME_REGEXP, message = com.hospital.citas.util.ValidationPatterns.NAME_MESSAGE)
    private String lastName;

    @Pattern(regexp = ValidationPatterns.PHONE_REGEXP_OPTIONAL, message = ValidationPatterns.PHONE_MESSAGE)
    private String phone;
}
