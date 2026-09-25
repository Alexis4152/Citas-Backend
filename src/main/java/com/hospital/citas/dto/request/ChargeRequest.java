package com.hospital.citas.dto.request;

import com.hospital.citas.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Cobro en recepción de una cita ya atendida. */
@Data
public class ChargeRequest {
    /** CASH, CARD_TERMINAL u OPENPAY_CARD. */
    @NotNull(message = "El método de pago es obligatorio")
    private PaymentMethod method;

    /** Solo se usa si la cita no tiene precio definido (el doctor no lo había puesto). */
    @DecimalMin(value = "0.01", message = "El monto debe ser mayor a cero")
    @DecimalMax(value = "999999.99", message = "El monto es demasiado alto")
    private BigDecimal amount;

    /** Efectivo: lo que entregó el cliente (para calcular el cambio). */
    @DecimalMin(value = "0.00", message = "El efectivo recibido no es válido")
    @DecimalMax(value = "9999999.99", message = "El efectivo recibido es demasiado alto")
    private BigDecimal cashReceived;

    /** Tarjeta en terminal: folio o autorización del voucher (opcional). */
    @Size(max = 100, message = "La referencia no puede superar 100 caracteres")
    private String reference;

    @Size(max = 100)
    private String sourceId;

    @Size(max = 100)
    private String deviceSessionId;
}
