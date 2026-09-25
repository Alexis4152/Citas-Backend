package com.hospital.citas.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CashCutCloseRequest {
    /** Efectivo que se contó físicamente en la caja al cerrar. */
    @NotNull(message = "Captura el efectivo contado (puede ser 0)")
    @DecimalMin(value = "0.00", message = "El efectivo contado no puede ser negativo")
    @DecimalMax(value = "9999999.99", message = "El efectivo contado es demasiado alto")
    private BigDecimal countedCash;

    @Size(max = 500, message = "Las notas no pueden superar 500 caracteres")
    private String notes;
}
