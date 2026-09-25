package com.hospital.citas.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DoctorPriceRequest {
    /** Precio de la consulta; nulo o 0 = sin precio definido. */
    @DecimalMin(value = "0.00", message = "El precio no puede ser negativo")
    @DecimalMax(value = "999999.99", message = "El precio es demasiado alto")
    private BigDecimal price;
}
