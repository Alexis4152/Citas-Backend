package com.hospital.citas.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EmailConfigRequest {

    @NotNull(message = "El campo enabled es obligatorio")
    private boolean enabled;

    @NotBlank(message = "El host SMTP es obligatorio")
    @jakarta.validation.constraints.Size(max = 255, message = "El host SMTP es demasiado largo")
    private String smtpHost;

    @NotNull(message = "El puerto SMTP es obligatorio")
    private Integer smtpPort;

    private String smtpUsername;

    /** Campo write-only: en blanco significa "no cambiar la contraseña ya guardada". */
    @jakarta.validation.constraints.Size(max = 200, message = "La contraseña SMTP es demasiado larga")
    private String smtpPassword;

    @Email(regexp = com.hospital.citas.util.ValidationPatterns.EMAIL_REGEXP, message = "El correo remitente no es válido")
    private String fromAddress;

    @jakarta.validation.constraints.AssertTrue(message = "Para activar el envío de correos captura el usuario SMTP o el correo remitente")
    public boolean isSenderPresentWhenEnabled() {
        return !enabled || (smtpUsername != null && !smtpUsername.isBlank()) || (fromAddress != null && !fromAddress.isBlank());
    }

    @jakarta.validation.constraints.AssertTrue(message = "El puerto SMTP debe estar entre 1 y 65535")
    public boolean isPortValid() {
        return smtpPort == null || (smtpPort >= 1 && smtpPort <= 65535);
    }
}
