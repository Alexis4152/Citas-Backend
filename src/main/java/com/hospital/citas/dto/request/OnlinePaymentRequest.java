package com.hospital.citas.dto.request;

import com.hospital.citas.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Pago anticipado del paciente (tarjeta o SPEI) -- los datos de la tarjeta NUNCA llegan aquí:
 * el navegador los tokeniza directo con OpenPay y solo se recibe el token (sourceId). */
@Data
public class OnlinePaymentRequest {
    /** OPENPAY_CARD u OPENPAY_SPEI. */
    @NotNull(message = "El método de pago es obligatorio")
    private PaymentMethod method;

    @Size(max = 100)
    private String sourceId;

    @Size(max = 100)
    private String deviceSessionId;
}
