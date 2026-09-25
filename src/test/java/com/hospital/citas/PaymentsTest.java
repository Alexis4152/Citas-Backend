package com.hospital.citas;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.GuestAppointmentRequest;
import com.hospital.citas.dto.request.MedicalInfoRequest;
import com.hospital.citas.dto.response.AppointmentResponse;
import com.hospital.citas.dto.response.CashCutResponse;
import com.hospital.citas.dto.response.PaymentReportResponse;
import com.hospital.citas.dto.response.PaymentResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.*;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Precios, pago anticipado (OpenPay simulado), cobro en recepción, corte de caja, reembolsos. */
class PaymentsTest extends AbstractIntegrationTest {

    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private FakePaymentGatewayConfig.FakeGateway gateway;

    private static final String WEBHOOK_AUTH = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("openpay_webhook:test_webhook_password".getBytes());

    // ── Helpers ──────────────────────────────────────────────────

    private final Map<Long, String> emailByDoctor = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Branch> branchByDoctor = new java.util.concurrent.ConcurrentHashMap<>();

    private Doctor doctorWithPrice(String price) {
        Branch branch = createBranch("Sede-Pay");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Pay"));
        branchByDoctor.put(doctor.getId(), branch);
        emailByDoctor.put(doctor.getId(), doctor.getUser().getEmail());
        if (price != null) {
            doctor.setConsultationPrice(new BigDecimal(price));
            doctor = doctorRepository.save(doctor);
        }
        return doctor;
    }

    private Branch branchOf(Doctor doctor) {
        return branchByDoctor.get(doctor.getId());
    }

    private AppointmentResponse bookGuest(Doctor doctor, LocalDate date, LocalTime time) {
        GuestAppointmentRequest req = new GuestAppointmentRequest();
        req.setFirstName("Pago");
        req.setLastName("Prueba-" + UUID.randomUUID().toString().substring(0, 6));
        req.setPhone("55" + (10000000 + new java.util.Random().nextInt(89999999)));
        req.setDoctorId(doctor.getId());
        req.setBranchId(branchOf(doctor).getId());
        req.setAppointmentDate(date);
        req.setStartTime(time);
        MedicalInfoRequest info = new MedicalInfoRequest();
        info.setAllergies("Ninguna");
        info.setBloodType(BloodType.O_POSITIVE);
        req.setMedicalInfo(info);
        var response = restTemplate.exchange(baseUrl("/api/appointments/guest"), HttpMethod.POST, new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<AppointmentResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().getData();
    }

    private ResponseEntity<String> call(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl(path), method, new HttpEntity<>(body, headers), String.class);
    }

    private <T> ResponseEntity<ApiResponse<T>> callTyped(HttpMethod method, String path, Object body, String token,
                                                         ParameterizedTypeReference<ApiResponse<T>> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        // URI ya codificada: con un String, RestTemplate volvería a codificar el % de "#123".
        return restTemplate.exchange(java.net.URI.create(baseUrl(path)), method, new HttpEntity<>(body, headers), type);
    }

    private Appointment insertAppointment(Doctor doctor, LocalDateTime start, AppointmentStatus status, String price) {
        Patient patient = patientRepository.save(Patient.builder().firstName("Ins").lastName("Cobro-" + UUID.randomUUID().toString().substring(0, 5))
                .phone("55" + (10000000 + new java.util.Random().nextInt(89999999))).build());
        return appointmentRepository.save(Appointment.builder()
                .doctor(doctor).branch(branchOf(doctor)).patient(patient)
                .appointmentDate(start.toLocalDate()).startTime(start.toLocalTime()).endTime(start.toLocalTime().plusMinutes(30))
                .status(status).slotReleased(false).cancelToken(UUID.randomUUID())
                .price(price == null ? null : new BigDecimal(price)).build());
    }

    private Map<String, Object> card() {
        return Map.of("method", "OPENPAY_CARD", "sourceId", "tok_ok", "deviceSessionId", "dev-1");
    }

    // ── Precio ───────────────────────────────────────────────────

    @Test
    void appointmentKeepsThePriceItHadWhenBookedEvenIfDoctorChangesItLater() {
        Doctor doctor = doctorWithPrice("500.00");
        AppointmentResponse booked = bookGuest(doctor, LocalDate.now().plusDays(5), LocalTime.of(10, 0));
        assertThat(booked.getPrice()).isEqualByComparingTo("500.00");
        assertThat(booked.getPaymentStatus()).isEqualTo("UNPAID");

        String token = loginAndGetToken(emailByDoctor.get(doctor.getId()), "Doctor123!");
        assertThat(call(HttpMethod.PUT, "/api/doctor/me/price", Map.of("price", 800), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(appointmentRepository.findById(booked.getId()).orElseThrow().getPrice()).isEqualByComparingTo("500.00");
        assertThat(bookGuest(doctor, LocalDate.now().plusDays(5), LocalTime.of(11, 0)).getPrice()).isEqualByComparingTo("800.00");

        // 0 = sin precio definido
        call(HttpMethod.PUT, "/api/doctor/me/price", Map.of("price", 0), token);
        assertThat(doctorRepository.findById(doctor.getId()).orElseThrow().getConsultationPrice()).isNull();
    }

    // ── Pago anticipado ──────────────────────────────────────────

    @Test
    void guestPrepaysByCardAndCannotPayTwice() {
        Doctor doctor = doctorWithPrice("500.00");
        AppointmentResponse booked = bookGuest(doctor, LocalDate.now().plusDays(5), LocalTime.of(10, 0));

        var paid = callTyped(HttpMethod.POST, "/api/appointments/pay/" + booked.getCancelToken(), card(), null,
                new ParameterizedTypeReference<ApiResponse<PaymentResponse>>() {});
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody().getData().getStatus()).isEqualTo("COMPLETED");
        assertThat(paid.getBody().getData().getChannel()).isEqualTo("ONLINE");
        assertThat(appointmentRepository.findById(booked.getId()).orElseThrow().getPaymentStatus()).isEqualTo(AppointmentPaymentStatus.PAID);

        ResponseEntity<String> again = call(HttpMethod.POST, "/api/appointments/pay/" + booked.getCancelToken(), card(), null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(again.getBody()).contains("ya está pagada");
    }

    @Test
    void declinedCardLeavesTheAppointmentUnpaidAndDoctorWithoutPriceCannotBePrepaid() {
        Doctor priced = doctorWithPrice("500.00");
        AppointmentResponse booked = bookGuest(priced, LocalDate.now().plusDays(5), LocalTime.of(10, 0));
        ResponseEntity<String> declined = call(HttpMethod.POST, "/api/appointments/pay/" + booked.getCancelToken(),
                Map.of("method", "OPENPAY_CARD", "sourceId", "tok_declined", "deviceSessionId", "dev-1"), null);
        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(declined.getBody()).contains("rechazado");
        assertThat(appointmentRepository.findById(booked.getId()).orElseThrow().getPaymentStatus()).isEqualTo(AppointmentPaymentStatus.UNPAID);
        assertThat(paymentRepository.findByAppointment_IdOrderByCreatedAtDesc(booked.getId())).isEmpty();

        Doctor free = doctorWithPrice(null);
        AppointmentResponse noPrice = bookGuest(free, LocalDate.now().plusDays(5), LocalTime.of(10, 0));
        assertThat(noPrice.getPrice()).isNull();
        ResponseEntity<String> refused = call(HttpMethod.POST, "/api/appointments/pay/" + noPrice.getCancelToken(), card(), null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("precio");
    }

    @Test
    void speiStaysPendingUntilTheWebhookConfirmsIt() {
        Doctor doctor = doctorWithPrice("500.00");
        AppointmentResponse booked = bookGuest(doctor, LocalDate.now().plusDays(5), LocalTime.of(10, 0));

        var spei = callTyped(HttpMethod.POST, "/api/appointments/pay/" + booked.getCancelToken(), Map.of("method", "OPENPAY_SPEI"), null,
                new ParameterizedTypeReference<ApiResponse<PaymentResponse>>() {});
        PaymentResponse payment = spei.getBody().getData();
        assertThat(payment.getStatus()).isEqualTo("PENDING");
        assertThat(payment.getSpeiClabe()).isNotBlank();
        assertThat(appointmentRepository.findById(booked.getId()).orElseThrow().getPaymentStatus()).isEqualTo(AppointmentPaymentStatus.PENDING);

        String txId = paymentRepository.findById(payment.getId()).orElseThrow().getOpenpayTransactionId();
        Map<String, Object> event = Map.of("type", "charge.succeeded", "transaction", Map.of("id", txId, "authorization", "AUTH-SPEI", "status", "completed"));

        // Sin credenciales, el webhook se rechaza.
        assertThat(call(HttpMethod.POST, "/api/webhooks/openpay", event, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, WEBHOOK_AUTH);
        assertThat(restTemplate.exchange(baseUrl("/api/webhooks/openpay"), HttpMethod.POST, new HttpEntity<>(event, headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(appointmentRepository.findById(booked.getId()).orElseThrow().getPaymentStatus()).isEqualTo(AppointmentPaymentStatus.PAID);
    }

    // ── Cancelación y reembolso ──────────────────────────────────

    @Test
    void cancellingAPaidAppointmentWithAtLeastAnHourRefundsAutomatically() {
        Doctor doctor = doctorWithPrice("500.00");
        AppointmentResponse booked = bookGuest(doctor, LocalDate.now().plusDays(5), LocalTime.of(10, 0));
        call(HttpMethod.POST, "/api/appointments/pay/" + booked.getCancelToken(), card(), null);
        int refundsBefore = gateway.refunds.size();

        assertThat(call(HttpMethod.POST, "/api/appointments/cancel/" + booked.getCancelToken(), Map.of(), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        Payment payment = paymentRepository.findByAppointment_IdOrderByCreatedAtDesc(booked.getId()).get(0);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundStatus()).isEqualTo(RefundStatus.REFUNDED);
        assertThat(appointmentRepository.findById(booked.getId()).orElseThrow().getPaymentStatus()).isEqualTo(AppointmentPaymentStatus.REFUNDED);
        assertThat(gateway.refunds.size()).isEqualTo(refundsBefore + 1);
    }

    @Test
    void cancellingWithLessThanAnHourLeavesTheRefundForTheAdministratorToDecide() {
        Doctor doctor = doctorWithPrice("500.00");
        Appointment soon = insertAppointment(doctor, LocalDateTime.now().plusMinutes(30), AppointmentStatus.SCHEDULED, "500.00");
        Payment payment = paymentRepository.save(Payment.builder().appointment(soon).amount(new BigDecimal("500.00"))
                .method(PaymentMethod.OPENPAY_CARD).status(PaymentStatus.COMPLETED).channel(PaymentChannel.ONLINE)
                .openpayTransactionId("fake-tx-late").build());
        soon.setPaymentStatus(AppointmentPaymentStatus.PAID);
        soon = appointmentRepository.save(soon);
        int refundsBefore = gateway.refunds.size();

        assertThat(call(HttpMethod.POST, "/api/appointments/cancel/" + soon.getCancelToken(), Map.of(), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getRefundStatus()).isEqualTo(RefundStatus.REVIEW);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(gateway.refunds.size()).isEqualTo(refundsBefore); // no se reembolsó solo

        String admin = adminToken();
        var review = callTyped(HttpMethod.GET, "/api/admin/payments/review", null, admin, new ParameterizedTypeReference<ApiResponse<List<PaymentResponse>>>() {});
        assertThat(review.getBody().getData()).extracting(PaymentResponse::getId).contains(payment.getId());

        var refunded = callTyped(HttpMethod.POST, "/api/admin/payments/" + payment.getId() + "/refund",
                Map.of("reason", "Acuerdo con el paciente", "manual", false), admin, new ParameterizedTypeReference<ApiResponse<PaymentResponse>>() {});
        assertThat(refunded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refunded.getBody().getData().getRefundStatus()).isEqualTo("REFUNDED");
        assertThat(gateway.refunds.size()).isEqualTo(refundsBefore + 1);
    }

    // ── Cobro en recepción y corte de caja ───────────────────────

    @Test
    void receptionMustOpenACashCutAndOnlyChargesAttendedAppointments() {
        Doctor doctor = doctorWithPrice("500.00");
        String reception = receptionistToken();
        Appointment scheduled = insertAppointment(doctor, LocalDateTime.now().plusDays(1), AppointmentStatus.SCHEDULED, "500.00");
        Appointment attended = insertAppointment(doctor, LocalDateTime.now().minusHours(2), AppointmentStatus.COMPLETED, "500.00");
        Map<String, Object> cash = Map.of("method", "CASH", "cashReceived", 500);

        // Sin corte abierto no se puede cobrar.
        ResponseEntity<String> noCut = call(HttpMethod.POST, "/api/reception/appointments/" + attended.getId() + "/charge", cash, reception);
        assertThat(noCut.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noCut.getBody()).contains("corte de caja");

        assertThat(call(HttpMethod.POST, "/api/reception/cash-cut/open", Map.of("openingAmount", 200), reception).getStatusCode()).isEqualTo(HttpStatus.OK);
        // No se abre un segundo corte.
        assertThat(call(HttpMethod.POST, "/api/reception/cash-cut/open", Map.of("openingAmount", 0), reception).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Una cita todavía programada no se cobra.
        ResponseEntity<String> notAttended = call(HttpMethod.POST, "/api/reception/appointments/" + scheduled.getId() + "/charge", cash, reception);
        assertThat(notAttended.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(notAttended.getBody()).contains("atendida");
    }

    @Test
    void cashCutTotalsExpectedCashDifferenceAndPerDoctorBreakdown() {
        Doctor doctorA = doctorWithPrice("500.00");
        Doctor doctorB = doctorWithPrice("300.00");
        String reception = receptionistToken();
        call(HttpMethod.POST, "/api/reception/cash-cut/open", Map.of("openingAmount", 200), reception);

        Appointment a1 = insertAppointment(doctorA, LocalDateTime.now().minusHours(3), AppointmentStatus.COMPLETED, "500.00");
        Appointment a2 = insertAppointment(doctorA, LocalDateTime.now().minusHours(2), AppointmentStatus.COMPLETED, "500.00");
        Appointment b1 = insertAppointment(doctorB, LocalDateTime.now().minusHours(1), AppointmentStatus.COMPLETED, "300.00");

        // Efectivo insuficiente -> rechazado; suficiente -> calcula el cambio.
        assertThat(call(HttpMethod.POST, "/api/reception/appointments/" + a1.getId() + "/charge", Map.of("method", "CASH", "cashReceived", 400), reception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var cashPayment = callTyped(HttpMethod.POST, "/api/reception/appointments/" + a1.getId() + "/charge",
                Map.of("method", "CASH", "cashReceived", 600), reception, new ParameterizedTypeReference<ApiResponse<PaymentResponse>>() {});
        assertThat(cashPayment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cashPayment.getBody().getData().getChangeGiven()).isEqualByComparingTo("100.00");

        call(HttpMethod.POST, "/api/reception/appointments/" + a2.getId() + "/charge", Map.of("method", "CARD_TERMINAL", "reference", "VOUCHER-1"), reception);
        call(HttpMethod.POST, "/api/reception/appointments/" + b1.getId() + "/charge", card(), reception);
        // Ya pagada: no se cobra dos veces.
        assertThat(call(HttpMethod.POST, "/api/reception/appointments/" + a1.getId() + "/charge", Map.of("method", "CASH", "cashReceived", 500), reception).getBody())
                .contains("ya está pagada");

        var current = callTyped(HttpMethod.GET, "/api/reception/cash-cut/current", null, reception, new ParameterizedTypeReference<ApiResponse<CashCutResponse>>() {})
                .getBody().getData();
        assertThat(current.getTotalCash()).isEqualByComparingTo("500.00");
        assertThat(current.getTotalCardTerminal()).isEqualByComparingTo("500.00");
        assertThat(current.getTotalOpenpay()).isEqualByComparingTo("300.00");
        assertThat(current.getTotalCollected()).isEqualByComparingTo("1300.00");
        assertThat(current.getAppointmentsCount()).isEqualTo(3);
        assertThat(current.getExpectedCash()).isEqualByComparingTo("700.00"); // fondo 200 + efectivo 500
        assertThat(current.getByDoctor()).hasSize(2);
        assertThat(current.getByDoctor().stream().filter(l -> l.getAppointments() == 2).findFirst().orElseThrow().getTotal()).isEqualByComparingTo("1000.00");

        // Cierra contando 680: faltan 20.
        var closed = callTyped(HttpMethod.POST, "/api/reception/cash-cut/" + current.getId() + "/close", Map.of("countedCash", 680, "notes", "Faltante"), reception,
                new ParameterizedTypeReference<ApiResponse<CashCutResponse>>() {}).getBody().getData();
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getDifference()).isEqualByComparingTo("-20.00");
        assertThat(closed.getExpectedCash()).isEqualByComparingTo("700.00");
        assertThat(call(HttpMethod.POST, "/api/reception/cash-cut/" + current.getId() + "/close", Map.of("countedCash", 680), reception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Otra recepcionista no ve el corte ajeno, pero el admin sí.
        assertThat(call(HttpMethod.GET, "/api/reception/cash-cut/" + current.getId(), null, receptionistToken()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, "/api/reception/cash-cut/" + current.getId(), null, adminToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Reportes ─────────────────────────────────────────────────

    @Test
    void doctorReportIncludesOnlinePrepaymentsAndReceptionPaymentsOfOwnAppointmentsOnly() {
        Doctor doctor = doctorWithPrice("500.00");
        Doctor other = doctorWithPrice("300.00");
        String reception = receptionistToken();
        call(HttpMethod.POST, "/api/reception/cash-cut/open", Map.of("openingAmount", 0), reception);

        AppointmentResponse online = bookGuest(doctor, LocalDate.now().plusDays(5), LocalTime.of(10, 0));
        call(HttpMethod.POST, "/api/appointments/pay/" + online.getCancelToken(), card(), null);
        Appointment attended = insertAppointment(doctor, LocalDateTime.now().minusHours(2), AppointmentStatus.COMPLETED, "500.00");
        call(HttpMethod.POST, "/api/reception/appointments/" + attended.getId() + "/charge", Map.of("method", "CASH", "cashReceived", 500), reception);
        Appointment foreign = insertAppointment(other, LocalDateTime.now().minusHours(2), AppointmentStatus.COMPLETED, "300.00");
        call(HttpMethod.POST, "/api/reception/appointments/" + foreign.getId() + "/charge", Map.of("method", "CASH", "cashReceived", 300), reception);

        String today = LocalDate.now().toString();
        var report = callTyped(HttpMethod.GET, "/api/doctor/payments/report?from=" + today + "&to=" + today, null,
                loginAndGetToken(emailByDoctor.get(doctor.getId()), "Doctor123!"), new ParameterizedTypeReference<ApiResponse<PaymentReportResponse>>() {})
                .getBody().getData();
        assertThat(report.getTotal()).isEqualByComparingTo("1000.00"); // 500 en línea + 500 en efectivo, sin los 300 del otro doctor
        assertThat(report.getTotalOpenpayCard()).isEqualByComparingTo("500.00");
        assertThat(report.getTotalCash()).isEqualByComparingTo("500.00");
        assertThat(report.getByDoctor()).hasSize(1);

        var adminReport = callTyped(HttpMethod.GET, "/api/admin/payments/report?from=" + today + "&to=" + today + "&doctorId=" + other.getId(), null,
                adminToken(), new ParameterizedTypeReference<ApiResponse<PaymentReportResponse>>() {}).getBody().getData();
        assertThat(adminReport.getTotal()).isEqualByComparingTo("300.00");
    }

    // ── Módulo de cobro: búsqueda por QR / folio / nombre y cobro en el mostrador ──

    private List<AppointmentResponse> lookup(String q, String token) {
        var response = callTyped(HttpMethod.GET, "/api/reception/charge/lookup?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8),
                null, token, new ParameterizedTypeReference<ApiResponse<List<AppointmentResponse>>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().getData();
    }

    @Test
    void chargeModuleFindsAnAppointmentByQrUrlByFolioAndByPatientName() {
        Doctor doctor = doctorWithPrice("500.00");
        String reception = receptionistToken();
        Appointment appt = insertAppointment(doctor, LocalDateTime.now().plusMinutes(5), AppointmentStatus.SCHEDULED, "500.00");
        String patientLastName = patientRepository.findById(appt.getPatient().getId()).orElseThrow().getLastName();

        // El QR del comprobante trae la URL de confirmación con el código de la cita.
        assertThat(lookup("http://localhost:5175/cita-confirmada?token=" + appt.getCancelToken(), reception))
                .extracting(AppointmentResponse::getId).containsExactly(appt.getId());
        // Folio, con o sin #.
        assertThat(lookup("#" + appt.getId(), reception)).extracting(AppointmentResponse::getId).containsExactly(appt.getId());
        assertThat(lookup(String.valueOf(appt.getId()), reception)).hasSize(1);
        // Nombre del paciente.
        assertThat(lookup(patientLastName, reception)).extracting(AppointmentResponse::getId).contains(appt.getId());
        // Sin texto: la cola de cobro incluye las programadas de hoy sin pagar.
        assertThat(lookup("", reception)).extracting(AppointmentResponse::getId).contains(appt.getId());
        // Algo inexistente no encuentra nada.
        assertThat(lookup("#987654321", reception)).isEmpty();
    }

    @Test
    void patientAtTheCounterCanPayBeforeTheConsultationAndArrivalIsRegistered() {
        Doctor doctor = doctorWithPrice("500.00");
        String reception = receptionistToken();
        call(HttpMethod.POST, "/api/reception/cash-cut/open", Map.of("openingAmount", 0), reception);
        Appointment today = insertAppointment(doctor, LocalDateTime.now().plusMinutes(10), AppointmentStatus.SCHEDULED, "500.00");
        Appointment tomorrow = insertAppointment(doctor, LocalDateTime.now().plusDays(1), AppointmentStatus.SCHEDULED, "500.00");

        // Una cita de otro día no se cobra en el mostrador.
        assertThat(call(HttpMethod.POST, "/api/reception/appointments/" + tomorrow.getId() + "/charge", Map.of("method", "CASH", "cashReceived", 500), reception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(call(HttpMethod.POST, "/api/reception/appointments/" + today.getId() + "/charge", Map.of("method", "CASH", "cashReceived", 500), reception).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Appointment after = appointmentRepository.findById(today.getId()).orElseThrow();
        assertThat(after.getPaymentStatus()).isEqualTo(AppointmentPaymentStatus.PAID);
        assertThat(after.getArrivedAt()).isNotNull(); // quien paga en el mostrador ya llegó
        assertThat(after.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED); // sigue pendiente de atenderse
    }

    @Test
    void cancellingAnAppointmentAlreadyPaidAtReceptionFlagsTheMoneyForReturn() {
        Doctor doctor = doctorWithPrice("500.00");
        String reception = receptionistToken();
        call(HttpMethod.POST, "/api/reception/cash-cut/open", Map.of("openingAmount", 0), reception);
        Appointment appt = insertAppointment(doctor, LocalDateTime.now().plusMinutes(20), AppointmentStatus.SCHEDULED, "500.00");
        call(HttpMethod.POST, "/api/reception/appointments/" + appt.getId() + "/charge", Map.of("method", "CASH", "cashReceived", 500), reception);
        int refundsBefore = gateway.refunds.size();

        assertThat(call(HttpMethod.POST, "/api/reception/appointments/" + appt.getId() + "/cancel", Map.of(), reception).getStatusCode()).isEqualTo(HttpStatus.OK);

        Payment payment = paymentRepository.findByAppointment_IdOrderByCreatedAtDesc(appt.getId()).get(0);
        assertThat(payment.getRefundStatus()).isEqualTo(RefundStatus.REVIEW);
        assertThat(payment.getRefundReason()).contains("recepción");
        assertThat(gateway.refunds.size()).isEqualTo(refundsBefore); // no es un cargo de OpenPay
    }
}
