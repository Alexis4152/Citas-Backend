package com.hospital.citas.service.impl;

import com.hospital.citas.dto.request.AdminRefundRequest;
import com.hospital.citas.dto.request.ChargeRequest;
import com.hospital.citas.dto.request.OnlinePaymentRequest;
import com.hospital.citas.dto.response.PaymentReportResponse;
import com.hospital.citas.dto.response.PaymentResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.*;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.PaymentMapper;
import com.hospital.citas.payment.PaymentAggregator;
import com.hospital.citas.payment.PaymentGateway;
import com.hospital.citas.payment.PaymentGateway.GatewayCharge;
import com.hospital.citas.payment.PaymentGateway.GatewayResult;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.CashCutRepository;
import com.hospital.citas.repository.DoctorRepository;
import com.hospital.citas.repository.PaymentRepository;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reglas de cobro (ver PaymentService). Puntos clave:
 * <ul>
 *   <li>Los datos de la tarjeta nunca llegan aquí: OpenPay se cobra con el token que genera el
 *       navegador.</li>
 *   <li>El pago anticipado se hace DESPUÉS de agendar (la cita ya existe y su horario ya está
 *       apartado): si la tarjeta se declina, la cita sigue y se paga en recepción -- ningún cobro
 *       queda huérfano por un fallo al agendar.</li>
 *   <li>Cancelación de una cita pagada en línea: con 1 hora o más de anticipación se reembolsa a
 *       la tarjeta automáticamente; con menos (o si fue SPEI) queda por revisar por el
 *       administrador.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
    private static final String CURRENCY = "MXN";

    private final PaymentRepository paymentRepository;
    private final AppointmentRepository appointmentRepository;
    private final CashCutRepository cashCutRepository;
    private final DoctorRepository doctorRepository;
    private final PaymentGateway gateway;
    private final PaymentMapper paymentMapper;

    /** Minutos mínimos de anticipación para el reembolso automático al cancelar (1 hora). */
    @Value("${app.payments.auto-refund-min-minutes:60}")
    private int autoRefundMinMinutes;

    // ── Pago anticipado en línea ─────────────────────────────────

    @Override
    @Transactional
    public PaymentResponse prepayByToken(UUID token, OnlinePaymentRequest request) {
        Appointment appointment = appointmentRepository.findByCancelTokenWithDetails(token)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada"));
        return prepay(appointment, request);
    }

    @Override
    @Transactional
    public PaymentResponse prepayOwn(Long appointmentId, OnlinePaymentRequest request) {
        User user = requireCurrentUser();
        Appointment appointment = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada: " + appointmentId));
        User owner = appointment.getPatient().getUser();
        if (owner == null || !owner.getId().equals(user.getId())) {
            throw new BusinessException("No puedes pagar una cita que no es tuya");
        }
        return prepay(appointment, request);
    }

    private PaymentResponse prepay(Appointment appointment, OnlinePaymentRequest request) {
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new BusinessException("Solo se puede pagar por anticipado una cita programada");
        }
        BigDecimal price = appointment.getPrice();
        if (price == null || price.signum() <= 0) {
            throw new BusinessException("Este doctor todavía no tiene precio definido: podrás pagar en recepción");
        }
        if (appointment.getPaymentStatus() == AppointmentPaymentStatus.PAID) {
            throw new BusinessException("Esta cita ya está pagada");
        }
        PaymentMethod method = request.getMethod();
        if (method != PaymentMethod.OPENPAY_CARD && method != PaymentMethod.OPENPAY_SPEI) {
            throw new BusinessException("El pago anticipado solo admite tarjeta o transferencia SPEI");
        }
        if (method == PaymentMethod.OPENPAY_CARD && (isBlank(request.getSourceId()) || isBlank(request.getDeviceSessionId()))) {
            throw new BusinessException("Faltan los datos de la tarjeta. Vuelve a capturarla.");
        }
        // Un SPEI pendiente anterior se reemplaza por este nuevo intento.
        cancelPending(appointment, "Se generó un nuevo intento de pago");

        GatewayResult result = gateway.charge(new GatewayCharge(
                method == PaymentMethod.OPENPAY_CARD ? PaymentGateway.Kind.CARD : PaymentGateway.Kind.SPEI,
                request.getSourceId(), price, CURRENCY, "Consulta médica - cita #" + appointment.getId(),
                "CITA-" + appointment.getId() + "-" + System.currentTimeMillis(), request.getDeviceSessionId(),
                customerOf(appointment.getPatient())));

        Payment payment = Payment.builder()
                .appointment(appointment).amount(price).method(method).channel(PaymentChannel.ONLINE)
                .openpayTransactionId(result.id()).authorizationCode(result.authorization())
                .build();
        if (method == PaymentMethod.OPENPAY_CARD) {
            if (result.status() != PaymentGateway.Status.COMPLETED) {
                throw new BusinessException("El pago no pudo confirmarse"
                        + (result.errorMessage() != null ? ": " + result.errorMessage() : ". Intenta de nuevo o paga en recepción."));
            }
            payment.setStatus(PaymentStatus.COMPLETED);
            appointment.setPaymentStatus(AppointmentPaymentStatus.PAID);
        } else {
            payment.setStatus(result.status() == PaymentGateway.Status.COMPLETED ? PaymentStatus.COMPLETED : PaymentStatus.PENDING);
            payment.setSpeiClabe(result.clabe());
            payment.setSpeiBank(result.bank());
            appointment.setPaymentStatus(payment.getStatus() == PaymentStatus.COMPLETED
                    ? AppointmentPaymentStatus.PAID : AppointmentPaymentStatus.PENDING);
        }
        appointmentRepository.save(appointment);
        return paymentMapper.toResponse(paymentRepository.save(payment));
    }

    // ── Cobro en recepción ───────────────────────────────────────

    @Override
    @Transactional
    public PaymentResponse charge(Long appointmentId, ChargeRequest request) {
        User actor = requireCurrentUser();
        Appointment appointment = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada: " + appointmentId));
        checkReceptionistSpecialtyAllowed(actor, appointment.getDoctor());
        // Se cobra una cita ya atendida, o una programada de HOY (el paciente paga en el mostrador
        // antes de pasar con el doctor: al cobrar se registra su llegada).
        boolean scheduledToday = appointment.getStatus() == AppointmentStatus.SCHEDULED
                && appointment.getAppointmentDate().equals(LocalDate.now());
        if (appointment.getStatus() != AppointmentStatus.COMPLETED && !scheduledToday) {
            throw new BusinessException("Solo se cobra una cita ya atendida o una programada para hoy");
        }
        if (appointment.getPaymentStatus() == AppointmentPaymentStatus.PAID) {
            throw new BusinessException("Esta cita ya está pagada");
        }
        CashCut cut = cashCutRepository.findByUser_IdAndStatus(actor.getId(), CashCutStatus.OPEN)
                .orElseThrow(() -> new BusinessException("Abre tu corte de caja antes de cobrar"));

        BigDecimal amount = appointment.getPrice() != null && appointment.getPrice().signum() > 0
                ? appointment.getPrice() : request.getAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("El doctor no tiene precio definido: captura el monto a cobrar");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);

        Payment payment = Payment.builder()
                .appointment(appointment).amount(amount).method(request.getMethod()).channel(PaymentChannel.RECEPTION)
                .status(PaymentStatus.COMPLETED).cashCut(cut).receivedBy(actor)
                .build();
        switch (request.getMethod()) {
            case CASH -> {
                BigDecimal received = request.getCashReceived();
                if (received == null || received.compareTo(amount) < 0) {
                    throw new BusinessException("El efectivo recibido no alcanza para cubrir el total");
                }
                payment.setCashReceived(received.setScale(2, RoundingMode.HALF_UP));
                payment.setChangeGiven(received.subtract(amount).setScale(2, RoundingMode.HALF_UP));
            }
            case CARD_TERMINAL -> payment.setReference(isBlank(request.getReference()) ? null : request.getReference().trim());
            case OPENPAY_CARD -> {
                if (isBlank(request.getSourceId()) || isBlank(request.getDeviceSessionId())) {
                    throw new BusinessException("Faltan los datos de la tarjeta. Vuelve a capturarla.");
                }
                GatewayResult result = gateway.charge(new GatewayCharge(PaymentGateway.Kind.CARD, request.getSourceId(),
                        amount, CURRENCY, "Consulta médica - cita #" + appointment.getId(),
                        "CITA-" + appointment.getId() + "-" + System.currentTimeMillis(), request.getDeviceSessionId(),
                        customerOf(appointment.getPatient())));
                if (result.status() != PaymentGateway.Status.COMPLETED) {
                    throw new BusinessException("El pago con tarjeta no pudo confirmarse"
                            + (result.errorMessage() != null ? ": " + result.errorMessage() : ""));
                }
                payment.setOpenpayTransactionId(result.id());
                payment.setAuthorizationCode(result.authorization());
            }
            default -> throw new BusinessException("Método de cobro no válido en recepción");
        }
        // Si el cliente había generado una transferencia SPEI y al final paga aquí, ese intento se descarta.
        cancelPending(appointment, "Se cobró en recepción");
        if (appointment.getPrice() == null) {
            appointment.setPrice(amount);
        }
        if (scheduledToday && appointment.getArrivedAt() == null) {
            // Quien paga en el mostrador ya está en el hospital: nunca se debe marcar "no asistió".
            appointment.setArrivedAt(LocalDateTime.now());
        }
        appointment.setPaymentStatus(AppointmentPaymentStatus.PAID);
        appointmentRepository.save(appointment);
        return paymentMapper.toResponse(paymentRepository.save(payment));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentResponse> listForAppointment(Long appointmentId) {
        User actor = requireCurrentUser();
        Appointment appointment = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada: " + appointmentId));
        checkReceptionistSpecialtyAllowed(actor, appointment.getDoctor());
        return paymentRepository.findByAppointmentWithDetails(appointmentId).stream().map(paymentMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public PaymentResponse refreshFromGateway(Long paymentId) {
        Payment payment = findPayment(paymentId);
        if (payment.getStatus() != PaymentStatus.PENDING || payment.getOpenpayTransactionId() == null) {
            return paymentMapper.toResponse(payment);
        }
        GatewayResult result = gateway.getCharge(payment.getOpenpayTransactionId());
        switch (result.status()) {
            case COMPLETED -> markCompleted(payment, result.authorization());
            case FAILED, CANCELLED -> markNotPaid(payment, result.status() == PaymentGateway.Status.FAILED ? PaymentStatus.FAILED : PaymentStatus.CANCELLED,
                    result.errorMessage());
            default -> { /* sigue pendiente */ }
        }
        return paymentMapper.toResponse(payment);
    }

    // ── Cancelación y reembolsos ─────────────────────────────────

    @Override
    @Transactional
    public void handleCancellation(Appointment appointment) {
        LocalDateTime start = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime());
        boolean autoRefundAllowed = !LocalDateTime.now().isAfter(start.minusMinutes(autoRefundMinMinutes));
        for (Payment payment : paymentRepository.findByAppointment_IdOrderByCreatedAtDesc(appointment.getId())) {
            if (payment.getStatus() == PaymentStatus.PENDING) {
                payment.setStatus(PaymentStatus.CANCELLED);
                payment.setFailureReason("La cita se canceló antes de recibir la transferencia");
                paymentRepository.save(payment);
                appointment.setPaymentStatus(AppointmentPaymentStatus.UNPAID);
            } else if (payment.getStatus() == PaymentStatus.COMPLETED && payment.getRefundStatus() == RefundStatus.NONE) {
                if (payment.getMethod() == PaymentMethod.OPENPAY_CARD && payment.getChannel() == PaymentChannel.ONLINE && autoRefundAllowed) {
                    try {
                        gateway.refund(payment.getOpenpayTransactionId(), payment.getAmount(), "Cita cancelada con anticipación");
                        payment.setStatus(PaymentStatus.REFUNDED);
                        payment.setRefundStatus(RefundStatus.REFUNDED);
                        payment.setRefundReason("Reembolso automático: cita cancelada con al menos 1 hora de anticipación");
                        payment.setRefundedAt(LocalDateTime.now());
                        appointment.setPaymentStatus(AppointmentPaymentStatus.REFUNDED);
                    } catch (RuntimeException e) {
                        // La cancelación no debe fallar porque la pasarela no responda: queda por revisar.
                        log.warn("No se pudo reembolsar automáticamente el pago {}", payment.getId(), e);
                        payment.setRefundStatus(RefundStatus.REVIEW);
                        payment.setRefundReason("No se pudo reembolsar automáticamente (" + e.getMessage() + "): revisar");
                    }
                } else {
                    payment.setRefundStatus(RefundStatus.REVIEW);
                    payment.setRefundReason(payment.getChannel() == PaymentChannel.RECEPTION
                            ? "Se cobró en recepción y la cita se canceló: devolver el dinero al paciente"
                            : payment.getMethod() == PaymentMethod.OPENPAY_SPEI
                                    ? "Pago por transferencia SPEI: el reembolso se hace manualmente"
                                    : "Cancelación con menos de 1 hora de anticipación: a consideración del administrador");
                }
                paymentRepository.save(payment);
            }
        }
        appointmentRepository.save(appointment);
    }

    @Override
    @Transactional
    public PaymentResponse adminRefund(Long paymentId, AdminRefundRequest request) {
        User admin = requireCurrentUser();
        Payment payment = findPayment(paymentId);
        if (payment.getStatus() != PaymentStatus.COMPLETED || payment.getRefundStatus() == RefundStatus.REFUNDED) {
            throw new BusinessException("Solo se puede reembolsar un cobro completado que no se haya reembolsado");
        }
        if (!request.isManual()) {
            if (payment.getMethod() != PaymentMethod.OPENPAY_CARD) {
                throw new BusinessException("Este cobro no se puede reembolsar por la pasarela: marca 'ya lo devolví manualmente'");
            }
            gateway.refund(payment.getOpenpayTransactionId(), payment.getAmount(), request.getReason());
        }
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundStatus(RefundStatus.REFUNDED);
        payment.setRefundReason(request.getReason().trim() + (request.isManual() ? " (devuelto manualmente)" : ""));
        payment.setRefundedAt(LocalDateTime.now());
        payment.setRefundedBy(admin);
        Appointment appointment = payment.getAppointment();
        appointment.setPaymentStatus(AppointmentPaymentStatus.REFUNDED);
        appointmentRepository.save(appointment);
        return paymentMapper.toResponse(paymentRepository.save(payment));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentResponse> listRefundReview() {
        return paymentRepository.findPendingRefundReview().stream().map(paymentMapper::toResponse).toList();
    }

    // ── Webhook ──────────────────────────────────────────────────

    @Override
    @Transactional
    public void processWebhook(String type, String gatewayTransactionId, String authorization, String errorMessage) {
        var found = paymentRepository.findByOpenpayTransactionId(gatewayTransactionId);
        if (found.isEmpty()) {
            log.warn("Webhook de OpenPay para una transacción desconocida: {}", gatewayTransactionId);
            return;
        }
        Payment payment = found.get();
        switch (type == null ? "" : type) {
            case "charge.succeeded" -> markCompleted(payment, authorization);
            case "charge.failed" -> markNotPaid(payment, PaymentStatus.FAILED, errorMessage);
            case "charge.cancelled" -> markNotPaid(payment, PaymentStatus.CANCELLED, errorMessage);
            case "charge.refunded" -> {
                if (payment.getStatus() != PaymentStatus.REFUNDED) {
                    payment.setStatus(PaymentStatus.REFUNDED);
                    payment.setRefundStatus(RefundStatus.REFUNDED);
                    payment.setRefundedAt(LocalDateTime.now());
                    payment.setRefundReason(payment.getRefundReason() != null ? payment.getRefundReason() : "Reembolsado en OpenPay");
                    payment.getAppointment().setPaymentStatus(AppointmentPaymentStatus.REFUNDED);
                    appointmentRepository.save(payment.getAppointment());
                    paymentRepository.save(payment);
                }
            }
            default -> log.info("Evento de webhook no manejado: {}", type);
        }
    }

    private void markCompleted(Payment payment, String authorization) {
        if (payment.getStatus() == PaymentStatus.COMPLETED || payment.getStatus() == PaymentStatus.REFUNDED) {
            return;
        }
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setAuthorizationCode(authorization);
        Appointment appointment = payment.getAppointment();
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            // El cliente transfirió después de que la cita se canceló: no se le puede dejar sin respuesta.
            payment.setRefundStatus(RefundStatus.REVIEW);
            payment.setRefundReason("El pago SPEI llegó después de cancelar la cita: reembolsar manualmente");
        } else {
            appointment.setPaymentStatus(AppointmentPaymentStatus.PAID);
            appointmentRepository.save(appointment);
        }
        paymentRepository.save(payment);
    }

    private void markNotPaid(Payment payment, PaymentStatus status, String reason) {
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        payment.setStatus(status);
        payment.setFailureReason(reason);
        Appointment appointment = payment.getAppointment();
        if (appointment.getPaymentStatus() == AppointmentPaymentStatus.PENDING) {
            appointment.setPaymentStatus(AppointmentPaymentStatus.UNPAID);
            appointmentRepository.save(appointment);
        }
        paymentRepository.save(payment);
    }

    // ── Reportes ─────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PaymentReportResponse reportAsAdmin(LocalDate from, LocalDate to, Long doctorId) {
        return buildReport(from, to, doctorId);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentReportResponse reportForOwnDoctor(LocalDate from, LocalDate to) {
        User user = requireCurrentUser();
        Doctor doctor = doctorRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe un perfil de doctor para este usuario"));
        return buildReport(from, to, doctor.getId());
    }

    private PaymentReportResponse buildReport(LocalDate from, LocalDate to, Long doctorId) {
        LocalDate start = from != null ? from : LocalDate.now();
        LocalDate end = to != null ? to : start;
        if (end.isBefore(start)) {
            throw new BusinessException("La fecha final no puede ser anterior a la inicial");
        }
        List<Payment> payments = paymentRepository.findForReport(start.atStartOfDay(), end.plusDays(1).atStartOfDay(), doctorId);
        BigDecimal cash = PaymentAggregator.sumMethod(payments, PaymentMethod.CASH);
        BigDecimal terminal = PaymentAggregator.sumMethod(payments, PaymentMethod.CARD_TERMINAL);
        BigDecimal opCard = PaymentAggregator.sumMethod(payments, PaymentMethod.OPENPAY_CARD);
        BigDecimal opSpei = PaymentAggregator.sumMethod(payments, PaymentMethod.OPENPAY_SPEI);
        BigDecimal refunded = payments.stream().filter(p -> p.getStatus() == PaymentStatus.REFUNDED)
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return PaymentReportResponse.builder()
                .from(start).to(end)
                .appointments(PaymentAggregator.distinctAppointments(payments))
                .totalCash(cash).totalCardTerminal(terminal).totalOpenpayCard(opCard).totalOpenpaySpei(opSpei)
                .total(cash.add(terminal).add(opCard).add(opSpei))
                .totalRefunded(refunded)
                .byDoctor(PaymentAggregator.byDoctor(payments))
                .payments(payments.stream().limit(500).map(paymentMapper::toResponse).toList())
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void cancelPending(Appointment appointment, String reason) {
        for (Payment p : paymentRepository.findByAppointment_IdOrderByCreatedAtDesc(appointment.getId())) {
            if (p.getStatus() == PaymentStatus.PENDING) {
                p.setStatus(PaymentStatus.CANCELLED);
                p.setFailureReason(reason);
                paymentRepository.save(p);
            }
        }
        if (appointment.getPaymentStatus() == AppointmentPaymentStatus.PENDING) {
            appointment.setPaymentStatus(AppointmentPaymentStatus.UNPAID);
        }
    }

    private PaymentGateway.Customer customerOf(Patient patient) {
        String email = isBlank(patient.getEmail()) ? "cliente@sin-correo.com" : patient.getEmail();
        String phone = patient.getPhone() == null ? "" : patient.getPhone().replaceAll("\\D", "");
        return new PaymentGateway.Customer(patient.getFirstName(), patient.getLastName(), email, phone);
    }

    private Payment findPayment(Long id) {
        return paymentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cobro no encontrado: " + id));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private User requireCurrentUser() {
        User user = SecurityUtils.getCurrentUserOrNull();
        if (user == null) {
            throw new ResourceNotFoundException("No hay sesión activa");
        }
        return user;
    }

    /** Igual que en AppointmentServiceImpl: una recepcionista acotada a ciertas especialidades
     * solo cobra citas de esos doctores (nunca aplica a ADMIN). */
    private void checkReceptionistSpecialtyAllowed(User actor, Doctor doctor) {
        if (actor.getRole().getName() != RoleName.RECEPTIONIST || actor.getSpecialties().isEmpty()) {
            return;
        }
        boolean allowed = actor.getSpecialties().stream().anyMatch(s -> s.getId().equals(doctor.getSpecialty().getId()));
        if (!allowed) {
            throw new BusinessException("No puedes cobrar citas de la especialidad " + doctor.getSpecialty().getName());
        }
    }
}
