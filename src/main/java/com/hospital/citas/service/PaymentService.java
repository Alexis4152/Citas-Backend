package com.hospital.citas.service;

import com.hospital.citas.dto.request.AdminRefundRequest;
import com.hospital.citas.dto.request.ChargeRequest;
import com.hospital.citas.dto.request.OnlinePaymentRequest;
import com.hospital.citas.dto.response.PaymentReportResponse;
import com.hospital.citas.dto.response.PaymentResponse;
import com.hospital.citas.entity.Appointment;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Cobros de citas: pago anticipado en línea (OpenPay tarjeta/SPEI), cobro en recepción
 * (efectivo, tarjeta en terminal u OpenPay), reembolsos y reportes de ingresos. */
public interface PaymentService {

    /** Pago anticipado del invitado, autorizado con el token de su cita. */
    PaymentResponse prepayByToken(UUID token, OnlinePaymentRequest request);

    /** Pago anticipado del paciente con cuenta, sobre su propia cita. */
    PaymentResponse prepayOwn(Long appointmentId, OnlinePaymentRequest request);

    /** Recepción/admin cobra una cita ya atendida; exige un corte de caja abierto. */
    PaymentResponse charge(Long appointmentId, ChargeRequest request);

    List<PaymentResponse> listForAppointment(Long appointmentId);

    /** Sincroniza con OpenPay un pago pendiente (SPEI) sin esperar el webhook. */
    PaymentResponse refreshFromGateway(Long paymentId);

    /** Se llama al cancelar una cita: reembolsa el pago anticipado (si se canceló con al menos
     * 1 hora de anticipación) o lo deja por revisar por el administrador; cancela SPEI pendientes. */
    void handleCancellation(Appointment appointment);

    PaymentResponse adminRefund(Long paymentId, AdminRefundRequest request);

    List<PaymentResponse> listRefundReview();

    /** Evento de OpenPay (webhook), ya autenticado por el controlador. */
    void processWebhook(String type, String gatewayTransactionId, String authorization, String errorMessage);

    PaymentReportResponse reportAsAdmin(LocalDate from, LocalDate to, Long doctorId);

    PaymentReportResponse reportForOwnDoctor(LocalDate from, LocalDate to);
}
