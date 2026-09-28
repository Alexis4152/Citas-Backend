package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import com.hospital.citas.enums.PaymentChannel;
import com.hospital.citas.enums.PaymentMethod;
import com.hospital.citas.enums.PaymentStatus;
import com.hospital.citas.enums.RefundStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Un cobro de una cita. Nunca guarda datos de tarjeta: el pago con OpenPay recibe solo el
 * token que genera el navegador. */
@Entity
@Table(name = "payments")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Payment extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 10)
    @Builder.Default
    private RefundStatus refundStatus = RefundStatus.NONE;

    @Column(name = "refund_reason", length = 500)
    private String refundReason;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refunded_by_user_id")
    private User refundedBy;

    /** Solo efectivo: lo que entregó el cliente y el cambio que se le devolvió. */
    @Column(name = "cash_received", precision = 10, scale = 2)
    private BigDecimal cashReceived;

    @Column(name = "change_given", precision = 10, scale = 2)
    private BigDecimal changeGiven;

    /** Tarjeta en terminal: folio/autorización del voucher (opcional). */
    @Column(length = 100)
    private String reference;

    @Column(name = "openpay_transaction_id", length = 100)
    private String openpayTransactionId;

    @Column(name = "authorization_code", length = 50)
    private String authorizationCode;

    @Column(name = "spei_clabe", length = 30)
    private String speiClabe;

    @Column(name = "spei_bank", length = 60)
    private String speiBank;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** Corte de caja en el que entró este cobro (solo cobros de recepción; el pago anticipado
     * en línea no pasa por la caja). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_cut_id")
    private CashCut cashCut;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by_user_id")
    private User receivedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
