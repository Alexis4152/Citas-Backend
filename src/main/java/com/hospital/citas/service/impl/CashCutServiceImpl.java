package com.hospital.citas.service.impl;

import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.CashCutCloseRequest;
import com.hospital.citas.dto.request.CashCutOpenRequest;
import com.hospital.citas.dto.response.CashCutResponse;
import com.hospital.citas.entity.CashCut;
import com.hospital.citas.entity.Payment;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.CashCutStatus;
import com.hospital.citas.enums.PaymentMethod;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.PaymentMapper;
import com.hospital.citas.payment.PaymentAggregator;
import com.hospital.citas.repository.CashCutRepository;
import com.hospital.citas.repository.PaymentRepository;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.CashCutService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CashCutServiceImpl implements CashCutService {

    private final CashCutRepository cashCutRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    @Override
    @Transactional(readOnly = true)
    public CashCutResponse current() {
        User user = requireCurrentUser();
        return cashCutRepository.findByUser_IdAndStatus(user.getId(), CashCutStatus.OPEN)
                .map(cut -> toResponse(cut, true))
                .orElse(null);
    }

    @Override
    @Transactional
    public CashCutResponse open(CashCutOpenRequest request) {
        User user = requireCurrentUser();
        if (cashCutRepository.findByUser_IdAndStatus(user.getId(), CashCutStatus.OPEN).isPresent()) {
            throw new BusinessException("Ya tienes un corte de caja abierto: ciérralo antes de abrir otro");
        }
        CashCut cut = CashCut.builder()
                .user(user)
                .openingAmount(request.getOpeningAmount().setScale(2, RoundingMode.HALF_UP))
                .openingNotes(blankToNull(request.getNotes()))
                .build();
        return toResponse(cashCutRepository.save(cut), true);
    }

    @Override
    @Transactional
    public CashCutResponse close(Long cutId, CashCutCloseRequest request) {
        User actor = requireCurrentUser();
        CashCut cut = findCut(cutId);
        requireOwnerOrAdmin(cut, actor);
        if (cut.getStatus() != CashCutStatus.OPEN) {
            throw new BusinessException("Este corte ya está cerrado");
        }
        List<Payment> payments = paymentRepository.findByCashCutWithDetails(cut.getId());
        BigDecimal cash = PaymentAggregator.sumMethod(payments, PaymentMethod.CASH);
        BigDecimal expected = cut.getOpeningAmount().add(cash);
        BigDecimal counted = request.getCountedCash().setScale(2, RoundingMode.HALF_UP);

        cut.setTotalCash(cash);
        cut.setTotalCardTerminal(PaymentAggregator.sumMethod(payments, PaymentMethod.CARD_TERMINAL));
        cut.setTotalOpenpay(PaymentAggregator.sumOpenpay(payments));
        cut.setAppointmentsCount(PaymentAggregator.distinctAppointments(payments));
        cut.setExpectedCash(expected);
        cut.setCountedCash(counted);
        cut.setDifference(counted.subtract(expected));
        cut.setClosingNotes(blankToNull(request.getNotes()));
        cut.setStatus(CashCutStatus.CLOSED);
        cut.setClosedAt(LocalDateTime.now());
        cut.setClosedBy(actor);
        return toResponse(cashCutRepository.save(cut), true);
    }

    @Override
    @Transactional(readOnly = true)
    public CashCutResponse get(Long cutId) {
        User actor = requireCurrentUser();
        CashCut cut = findCut(cutId);
        requireOwnerOrAdmin(cut, actor);
        return toResponse(cut, true);
    }

    @Override
    @Transactional(readOnly = true)
    public com.hospital.citas.report.ReportTable report(Long cutId) {
        CashCutResponse cut = get(cutId);
        var fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        var b = com.hospital.citas.report.ReportTable.builder("cash-cut", "Corte de caja #" + cut.getId())
                .subtitle(cut.getUserName() + " · abierto el " + cut.getOpenedAt().format(fmt)
                        + (cut.getClosedAt() != null ? " · cerrado el " + cut.getClosedAt().format(fmt) : " · sigue abierto"));
        java.util.function.Function<java.math.BigDecimal, String> m = v -> com.hospital.citas.report.ReportFileWriter.format(
                v == null ? java.math.BigDecimal.ZERO : v, com.hospital.citas.report.ReportTable.Type.MONEY);
        b.stat("Fondo inicial", m.apply(cut.getOpeningAmount()))
                .stat("Efectivo", m.apply(cut.getTotalCash()))
                .stat("Terminal", m.apply(cut.getTotalCardTerminal()))
                .stat("OpenPay", m.apply(cut.getTotalOpenpay()))
                .stat("Total cobrado", m.apply(cut.getTotalCollected()))
                .stat("Citas cobradas", String.valueOf(cut.getAppointmentsCount() == null ? 0 : cut.getAppointmentsCount()))
                .stat("Debe haber en caja", m.apply(cut.getExpectedCash()));
        if ("CLOSED".equals(cut.getStatus())) {
            b.stat("Contado", m.apply(cut.getCountedCash()));
            java.math.BigDecimal diff = cut.getDifference() == null ? java.math.BigDecimal.ZERO : cut.getDifference();
            b.stat(diff.signum() == 0 ? "Caja cuadrada" : diff.signum() < 0 ? "Faltante" : "Sobrante", m.apply(diff.abs()));
        }
        var MONEY = com.hospital.citas.report.ReportTable.Type.MONEY;
        var TEXT = com.hospital.citas.report.ReportTable.Type.TEXT;
        b.col("at", "Hora del cobro", com.hospital.citas.report.ReportTable.Type.DATETIME, 1.1f)
                .col("folio", "Folio cita", TEXT, 0.7f)
                .col("appt", "Cita", TEXT, 1.1f)
                .col("patient", "Paciente", TEXT, 1.7f)
                .col("doctor", "Doctor", TEXT, 1.6f)
                .col("method", "Método", TEXT, 1.1f)
                .col("received", "Recibido", MONEY, 0.85f)
                .col("change", "Cambio", MONEY, 0.8f)
                .col("reference", "Referencia", TEXT, 1f)
                .col("by", "Cobró", TEXT, 1.2f)
                .col("amount", "Monto", MONEY, 0.9f);
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        int count = 0;
        var dmy = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        for (var p : cut.getPayments()) {
            boolean refunded = "REFUNDED".equals(p.getStatus());
            if (!refunded) {
                total = total.add(p.getAmount());
                count++;
            }
            b.row(p.getCreatedAt(), "#" + String.format("%06d", p.getAppointmentId()),
                    (p.getAppointmentDate() == null ? "" : p.getAppointmentDate().format(dmy))
                            + (p.getAppointmentTime() == null ? "" : " " + p.getAppointmentTime().toString().substring(0, 5)),
                    p.getPatientName(), p.getDoctorName(),
                    com.hospital.citas.report.ReportCatalog.methodLabel(PaymentMethod.valueOf(p.getMethod())) + (refunded ? " (reembolsado)" : ""),
                    p.getCashReceived(), p.getChangeGiven(),
                    p.getReference() != null ? p.getReference() : p.getAuthorizationCode(),
                    p.getReceivedByName(), refunded ? java.math.BigDecimal.ZERO : p.getAmount());
        }
        b.totals("Total", null, null, count + " cobros", null, null, null, null, null, null, total);
        if (cut.getOpeningNotes() != null || cut.getClosingNotes() != null) {
            b.note((cut.getOpeningNotes() != null ? "Notas de apertura: " + cut.getOpeningNotes() + ". " : "")
                    + (cut.getClosingNotes() != null ? "Notas de cierre: " + cut.getClosingNotes() + "." : ""));
        }
        return b.build();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CashCutResponse> list(Pageable pageable) {
        User actor = requireCurrentUser();
        Page<CashCut> page = actor.getRole().getName() == RoleName.ADMIN
                ? cashCutRepository.findAll(pageable)
                : cashCutRepository.findByUser_Id(actor.getId(), pageable);
        return PageResponse.of(page.map(cut -> toResponse(cut, false)));
    }

    /** Cerrado: la foto guardada al cerrar. Abierto: se calcula en vivo con sus cobros. */
    private CashCutResponse toResponse(CashCut cut, boolean withDetails) {
        boolean closed = cut.getStatus() == CashCutStatus.CLOSED;
        List<Payment> payments = withDetails || !closed ? paymentRepository.findByCashCutWithDetails(cut.getId()) : List.of();
        BigDecimal cash = closed ? cut.getTotalCash() : PaymentAggregator.sumMethod(payments, PaymentMethod.CASH);
        BigDecimal terminal = closed ? cut.getTotalCardTerminal() : PaymentAggregator.sumMethod(payments, PaymentMethod.CARD_TERMINAL);
        BigDecimal openpay = closed ? cut.getTotalOpenpay() : PaymentAggregator.sumOpenpay(payments);
        Integer count = closed ? cut.getAppointmentsCount() : Integer.valueOf(PaymentAggregator.distinctAppointments(payments));
        BigDecimal expected = closed ? cut.getExpectedCash() : cut.getOpeningAmount().add(cash);
        return CashCutResponse.builder()
                .id(cut.getId())
                .status(cut.getStatus().name())
                .userName(cut.getUser().getFirstName() + " " + cut.getUser().getLastName())
                .openedAt(cut.getOpenedAt())
                .closedAt(cut.getClosedAt())
                .closedByName(cut.getClosedBy() == null ? null : cut.getClosedBy().getFirstName() + " " + cut.getClosedBy().getLastName())
                .openingAmount(cut.getOpeningAmount())
                .openingNotes(cut.getOpeningNotes())
                .closingNotes(cut.getClosingNotes())
                .totalCash(cash).totalCardTerminal(terminal).totalOpenpay(openpay)
                .totalCollected(cash.add(terminal).add(openpay))
                .appointmentsCount(count)
                .expectedCash(expected)
                .countedCash(cut.getCountedCash())
                .difference(cut.getDifference())
                .byDoctor(withDetails ? PaymentAggregator.byDoctor(payments) : null)
                .payments(withDetails ? payments.stream().map(paymentMapper::toResponse).toList() : null)
                .build();
    }

    private CashCut findCut(Long id) {
        return cashCutRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Corte no encontrado: " + id));
    }

    private void requireOwnerOrAdmin(CashCut cut, User actor) {
        if (actor.getRole().getName() != RoleName.ADMIN && !cut.getUser().getId().equals(actor.getId())) {
            throw new BusinessException("Ese corte de caja no es tuyo");
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private User requireCurrentUser() {
        User user = SecurityUtils.getCurrentUserOrNull();
        if (user == null) {
            throw new ResourceNotFoundException("No hay sesión activa");
        }
        return user;
    }
}
