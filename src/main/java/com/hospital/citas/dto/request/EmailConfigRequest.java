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
    private String smtpHost;

    @NotNull(message = "El puerto SMTP es obligatorio")
    private Integer smtpPort;

    private String smtpUsername;

    /** Campo write-only: en blanco significa "no cambiar la contraseña ya guardada". */
    private String smtpPassword;

    @Email(message = "El correo remitente no es válido")
    private String fromAddress;
}
