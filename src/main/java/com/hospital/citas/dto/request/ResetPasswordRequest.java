package com.hospital.citas.dto.request;

import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class ResetPasswordRequest {

    @NotNull(message = "El token es obligatorio")
    private UUID token;

    @NotBlank(message = "La contraseña es obligatoria")
    @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
    @Pattern(regexp = ValidationPatterns.PASSWORD_REGEXP, message = ValidationPatterns.PASSWORD_MESSAGE)
    private String newPassword;
}
