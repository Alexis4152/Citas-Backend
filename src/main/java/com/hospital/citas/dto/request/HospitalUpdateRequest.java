package com.hospital.citas.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Edición de un hospital por el SUPER_ADMIN. La llave privada de OpenPay es de solo escritura:
 * vacía significa "no cambiar la guardada" (nunca se regresa al navegador).
 */
@Data
public class HospitalUpdateRequest {

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 150)
    private String name;

    @NotBlank(message = "El identificador del enlace es obligatorio")
    @Size(min = 3, max = 60, message = "El identificador debe tener entre 3 y 60 caracteres")
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
            message = "El identificador solo admite minúsculas, números y guiones (ej. clinica-san-jose)")
    private String slug;

    @NotNull
    private Boolean isActive;

    @Size(max = 100)
    private String openpayMerchantId;

    @Size(max = 100)
    private String openpayPublicKey;

    /** Vacío = conservar la actual. */
    @Size(max = 100)
    private String openpayPrivateKey;

    /** true = quitar las llaves de OpenPay (el hospital vuelve a cobrar solo en recepción). */
    private boolean clearOpenpay;

    @NotNull
    private Boolean openpayProduction;
}
