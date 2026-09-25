package com.hospital.citas.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CashCutOpenRequest {
    /** Fondo inicial en efectivo con el que se abre la caja. */
    @NotNull(message = "El fondo inicial es obligatorio (puede ser 0)")
    @DecimalMin(value = "0.00", message = "El fondo inicial no puede ser negativo")
    @DecimalMax(value = "9999999.99", message = "El fondo inicial es demasiado alto")
    private BigDecimal openingAmount;

    @Size(max = 500, message = "Las notas no pueden superar 500 caracteres")
    private String notes;
}
