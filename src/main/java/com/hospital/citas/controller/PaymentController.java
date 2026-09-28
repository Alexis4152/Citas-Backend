package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.AdminRefundRequest;
import com.hospital.citas.dto.request.ChargeRequest;
import com.hospital.citas.dto.request.OnlinePaymentRequest;
import com.hospital.citas.dto.response.PaymentReportResponse;
import com.hospital.citas.dto.response.PaymentResponse;
import com.hospital.citas.entity.Hospital;
import com.hospital.citas.tenant.HospitalDirectory;
import com.hospital.citas.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cobros: pago anticipado (paciente/invitado), cobro en recepción, reembolsos y reportes. La
 * autorización de cada ruta la define SecurityConfig por prefijo de URL. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final HospitalDirectory hospitalDirectory;

    /** Llaves PÚBLICAS de OpenPay del hospital del link, para que el navegador tokenice la
     * tarjeta (la llave privada nunca sale del backend). {@code enabled=false}: ese hospital no
     * tiene pagos en línea y sus pacientes pagan en recepción. */
    @GetMapping("/public/payments/config")
    public ApiResponse<Map<String, Object>> config() {
        Hospital hospital = hospitalDirectory.current();
        boolean enabled = hospital.hasOpenpay();
        return ApiResponse.ok(Map.of(
                "enabled", enabled,
                "merchantId", enabled ? hospital.getOpenpayMerchantId().trim() : "",
                "publicKey", enabled ? hospital.getOpenpayPublicKey().trim() : "",
                "sandbox", !Boolean.TRUE.equals(hospital.getOpenpayProduction())));
    }

    /** Invitado: paga por anticipado con el token de su cita. */
    @PostMapping("/appointments/pay/{token}")
    public ApiResponse<PaymentResponse> prepayByToken(@PathVariable UUID token, @Valid @RequestBody OnlinePaymentRequest request) {
        return ApiResponse.ok(paymentService.prepayByToken(token, request), "Pago procesado");
    }

    /** Paciente con cuenta: paga por anticipado su propia cita. */
    @PostMapping("/appointments/{id}/pay")
    public ApiResponse<PaymentResponse> prepayOwn(@PathVariable Long id, @Valid @RequestBody OnlinePaymentRequest request) {
        return ApiResponse.ok(paymentService.prepayOwn(id, request), "Pago procesado");
    }

    // ── Recepción ────────────────────────────────────────────────

    @PostMapping("/reception/appointments/{id}/charge")
    public ResponseEntity<ApiResponse<PaymentResponse>> charge(@PathVariable Long id, @Valid @RequestBody ChargeRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(paymentService.charge(id, request), "Cobro registrado"));
    }

    @GetMapping("/reception/appointments/{id}/payments")
    public ApiResponse<List<PaymentResponse>> paymentsOf(@PathVariable Long id) {
        return ApiResponse.ok(paymentService.listForAppointment(id));
    }

    @PostMapping("/reception/payments/{id}/refresh")
    public ApiResponse<PaymentResponse> refresh(@PathVariable Long id) {
        return ApiResponse.ok(paymentService.refreshFromGateway(id));
    }

    // ── Doctor ───────────────────────────────────────────────────

    @GetMapping("/doctor/payments/report")
    public ApiResponse<PaymentReportResponse> doctorReport(@RequestParam(required = false) LocalDate from,
                                                            @RequestParam(required = false) LocalDate to) {
        return ApiResponse.ok(paymentService.reportForOwnDoctor(from, to));
    }

    // ── Admin ────────────────────────────────────────────────────

    @GetMapping("/admin/payments/report")
    public ApiResponse<PaymentReportResponse> adminReport(@RequestParam(required = false) LocalDate from,
                                                           @RequestParam(required = false) LocalDate to,
                                                           @RequestParam(required = false) Long doctorId) {
        return ApiResponse.ok(paymentService.reportAsAdmin(from, to, doctorId));
    }

    @GetMapping("/admin/payments/review")
    public ApiResponse<List<PaymentResponse>> refundReview() {
        return ApiResponse.ok(paymentService.listRefundReview());
    }

    @PostMapping("/admin/payments/{id}/refund")
    public ApiResponse<PaymentResponse> refund(@PathVariable Long id, @Valid @RequestBody AdminRefundRequest request) {
        return ApiResponse.ok(paymentService.adminRefund(id, request), "Reembolso registrado");
    }
}
