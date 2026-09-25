package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Corte de caja con sus totales. En el listado (historial) {@code byDoctor} y {@code payments}
 * van vacíos; en el detalle vienen completos. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CashCutResponse {
    private Long id;
    private String status;
    private String userName;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private String closedByName;
    private BigDecimal openingAmount;
    private String openingNotes;
    private String closingNotes;

    private BigDecimal totalCash;
    private BigDecimal totalCardTerminal;
    private BigDecimal totalOpenpay;
    /** Efectivo + tarjeta terminal + OpenPay. */
    private BigDecimal totalCollected;
    private Integer appointmentsCount;

    /** Lo que debe haber físicamente en la caja: fondo inicial + efectivo cobrado. */
    private BigDecimal expectedCash;
    /** Lo que se contó al cerrar (nulo mientras está abierto). */
    private BigDecimal countedCash;
    /** Contado - esperado: negativo = faltante, positivo = sobrante. */
    private BigDecimal difference;

    private List<DoctorLine> byDoctor;
    private List<PaymentResponse> payments;

    /** Citas cobradas y montos de un doctor dentro del corte/reporte. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DoctorLine {
        private Long doctorId;
        private String doctorName;
        private String specialtyName;
        private int appointments;
        private BigDecimal cash;
        private BigDecimal cardTerminal;
        /** OpenPay tarjeta + SPEI. */
        private BigDecimal openpay;
        private BigDecimal total;
    }
}
