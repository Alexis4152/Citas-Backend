package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentResponse {
    private Long id;
    private Long appointmentId;
    private BigDecimal amount;
    private String method;
    private String status;
    private String channel;
    private String refundStatus;
    private String refundReason;
    private LocalDateTime refundedAt;
    private BigDecimal cashReceived;
    private BigDecimal changeGiven;
    private String reference;
    private String authorizationCode;
    /** Datos para que el cliente haga su transferencia SPEI (solo pagos SPEI). */
    private String speiClabe;
    private String speiBank;
    private String failureReason;
    private LocalDateTime createdAt;
    private String receivedByName;
    private String patientName;
    private String doctorName;
    private String specialtyName;
    private LocalDate appointmentDate;
    private LocalTime appointmentTime;
}
