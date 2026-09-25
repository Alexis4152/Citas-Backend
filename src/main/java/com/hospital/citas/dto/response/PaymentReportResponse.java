package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Ingresos por cobros en un rango de fechas (pago anticipado en línea + cobros de recepción),
 * para el doctor (los suyos) y el admin (todos o por doctor). */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentReportResponse {
    private LocalDate from;
    private LocalDate to;
    private int appointments;
    private BigDecimal totalCash;
    private BigDecimal totalCardTerminal;
    private BigDecimal totalOpenpayCard;
    private BigDecimal totalOpenpaySpei;
    private BigDecimal total;
    /** Suma de los cobros ya reembolsados (no incluidos en {@code total}). */
    private BigDecimal totalRefunded;
    private List<CashCutResponse.DoctorLine> byDoctor;
    private List<PaymentResponse> payments;
}
