package com.hospital.citas.dto.request;

import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank(message = "La contraseña actual es obligatoria")
    private String currentPassword;

    @NotBlank(message = "La nueva contraseña es obligatoria")
    @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
    @Pattern(regexp = ValidationPatterns.PASSWORD_REGEXP, message = ValidationPatterns.PASSWORD_MESSAGE)
    private String newPassword;

    @jakarta.validation.constraints.AssertTrue(message = "La nueva contraseña debe ser distinta de la actual")
    public boolean isDifferentFromCurrent() {
        return currentPassword == null || newPassword == null || !currentPassword.equals(newPassword);
    }
}
