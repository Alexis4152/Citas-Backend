package com.hospital.citas.enums;

/** Estado de pago de una cita: UNPAID (por cobrar), PENDING (SPEI generado sin pagar aún),
 * PAID, REFUNDED. */
public enum AppointmentPaymentStatus {
    UNPAID, PENDING, PAID, REFUNDED
}
