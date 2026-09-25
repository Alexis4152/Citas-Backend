package com.hospital.citas.payment;

import java.math.BigDecimal;

/** Pasarela de pagos en línea (hoy OpenPay). Es una interfaz para poder sustituirla por una
 * simulada en las pruebas: ningún test toca la red. */
public interface PaymentGateway {

    /** Crea un cargo. Tarjeta: cobro inmediato con el token. SPEI: genera la CLABE y el cargo
     * queda en progreso hasta que el cliente transfiere. */
    GatewayResult charge(GatewayCharge charge);

    GatewayResult getCharge(String gatewayTransactionId);

    GatewayResult refund(String gatewayTransactionId, BigDecimal amount, String reason);

    enum Kind { CARD, SPEI }

    enum Status { COMPLETED, IN_PROGRESS, FAILED, CANCELLED, REFUNDED, PENDING }

    record Customer(String name, String lastName, String email, String phoneNumber) {}

    record GatewayCharge(Kind kind, String sourceId, BigDecimal amount, String currency, String description,
                         String orderId, String deviceSessionId, Customer customer) {}

    record GatewayResult(String id, Status status, String authorization, String clabe, String bank, String errorMessage) {}
}
