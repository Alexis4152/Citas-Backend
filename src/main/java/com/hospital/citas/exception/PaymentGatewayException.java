package com.hospital.citas.exception;

/** La pasarela de pagos no respondió o falló por un motivo que no es del cliente (502). */
public class PaymentGatewayException extends RuntimeException {
    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
