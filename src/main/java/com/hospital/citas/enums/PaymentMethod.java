package com.hospital.citas.enums;

/** Cómo se pagó una cita. CASH y CARD_TERMINAL se registran en recepción (la terminal física
 * es externa al sistema); OPENPAY_* pasan por la pasarela. */
public enum PaymentMethod {
    CASH, CARD_TERMINAL, OPENPAY_CARD, OPENPAY_SPEI
}
