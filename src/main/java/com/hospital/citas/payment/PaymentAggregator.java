package com.hospital.citas.payment;

import com.hospital.citas.dto.response.CashCutResponse.DoctorLine;
import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.Payment;
import com.hospital.citas.enums.PaymentMethod;
import com.hospital.citas.enums.PaymentStatus;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;

/** Sumas y desgloses por doctor de una lista de cobros -- compartido por el corte de caja y los
 * reportes de ingresos para que ambos cuenten exactamente igual. Solo cuentan los cobros
 * COMPLETADOS (un cobro reembolsado o pendiente no es dinero recibido). */
public final class PaymentAggregator {

    private PaymentAggregator() {
    }

    public static BigDecimal sum(Collection<Payment> payments, Predicate<Payment> filter) {
        return payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .filter(filter)
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal sumMethod(Collection<Payment> payments, PaymentMethod method) {
        return sum(payments, p -> p.getMethod() == method);
    }

    public static BigDecimal sumOpenpay(Collection<Payment> payments) {
        return sum(payments, p -> p.getMethod() == PaymentMethod.OPENPAY_CARD || p.getMethod() == PaymentMethod.OPENPAY_SPEI);
    }

    public static int distinctAppointments(Collection<Payment> payments) {
        return (int) payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .map(p -> p.getAppointment().getId())
                .distinct().count();
    }

    /** Un renglón por doctor: cuántas citas cobró y cuánto por método. Ordenado por nombre. */
    public static List<DoctorLine> byDoctor(Collection<Payment> payments) {
        Map<Long, List<Payment>> grouped = new LinkedHashMap<>();
        for (Payment p : payments) {
            if (p.getStatus() == PaymentStatus.COMPLETED) {
                grouped.computeIfAbsent(p.getAppointment().getDoctor().getId(), k -> new ArrayList<>()).add(p);
            }
        }
        List<DoctorLine> lines = new ArrayList<>();
        for (List<Payment> list : grouped.values()) {
            Doctor doctor = list.get(0).getAppointment().getDoctor();
            BigDecimal cash = sumMethod(list, PaymentMethod.CASH);
            BigDecimal card = sumMethod(list, PaymentMethod.CARD_TERMINAL);
            BigDecimal openpay = sumOpenpay(list);
            lines.add(DoctorLine.builder()
                    .doctorId(doctor.getId())
                    .doctorName("Dr(a). " + doctor.getUser().getFirstName() + " " + doctor.getUser().getLastName())
                    .specialtyName(doctor.getSpecialty().getName())
                    .appointments(distinctAppointments(list))
                    .cash(cash).cardTerminal(card).openpay(openpay)
                    .total(cash.add(card).add(openpay))
                    .build());
        }
        lines.sort(Comparator.comparing(DoctorLine::getDoctorName));
        return lines;
    }
}
