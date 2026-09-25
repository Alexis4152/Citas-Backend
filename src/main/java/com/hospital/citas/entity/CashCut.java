package com.hospital.citas.entity;

import com.hospital.citas.enums.CashCutStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Corte de caja: el turno de una persona de recepción (o admin), desde que lo abre con su
 * fondo inicial hasta que lo cierra contando el efectivo. Mientras está abierto los totales se
 * calculan en vivo a partir de sus cobros; al cerrar se guarda una foto. */
@Entity
@Table(name = "cash_cuts")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CashCut {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private CashCutStatus status = CashCutStatus.OPEN;

    @Column(name = "opened_at", nullable = false)
    @Builder.Default
    private LocalDateTime openedAt = LocalDateTime.now();

    @Column(name = "opening_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal openingAmount = BigDecimal.ZERO;

    @Column(name = "opening_notes", columnDefinition = "TEXT")
    private String openingNotes;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by_user_id")
    private User closedBy;

    @Column(name = "total_cash", precision = 12, scale = 2)
    private BigDecimal totalCash;

    @Column(name = "total_card_terminal", precision = 12, scale = 2)
    private BigDecimal totalCardTerminal;

    @Column(name = "total_openpay", precision = 12, scale = 2)
    private BigDecimal totalOpenpay;

    @Column(name = "appointments_count")
    private Integer appointmentsCount;

    @Column(name = "expected_cash", precision = 12, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "counted_cash", precision = 12, scale = 2)
    private BigDecimal countedCash;

    @Column(precision = 12, scale = 2)
    private BigDecimal difference;

    @Column(name = "closing_notes", columnDefinition = "TEXT")
    private String closingNotes;
}
