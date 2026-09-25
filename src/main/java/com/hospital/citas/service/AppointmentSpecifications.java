package com.hospital.citas.service;

import com.hospital.citas.entity.Appointment;
import com.hospital.citas.enums.AppointmentStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class AppointmentSpecifications {

    private AppointmentSpecifications() {
    }

    public static Specification<Appointment> search(Long doctorId, Long branchId, LocalDate from, LocalDate to,
                                                      AppointmentStatus status, String patientQuery) {
        return search(doctorId, null, branchId, from, to, status, patientQuery);
    }

    public static Specification<Appointment> search(Long doctorId, Long patientId, Long branchId, LocalDate from, LocalDate to,
                                                      AppointmentStatus status, String patientQuery) {
        return search(doctorId, patientId, branchId, null, from, to, status, patientQuery);
    }

    public static Specification<Appointment> search(Long doctorId, Long patientId, Long branchId, Long specialtyId,
                                                      LocalDate from, LocalDate to, AppointmentStatus status, String patientQuery) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (doctorId != null) {
                predicates.add(cb.equal(root.get("doctor").get("id"), doctorId));
            }
            if (patientId != null) {
                predicates.add(cb.equal(root.get("patient").get("id"), patientId));
            }
            if (branchId != null) {
                predicates.add(cb.equal(root.get("branch").get("id"), branchId));
            }
            if (specialtyId != null) {
                predicates.add(cb.equal(root.get("doctor").get("specialty").get("id"), specialtyId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("appointmentDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("appointmentDate"), to));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (patientQuery != null && !patientQuery.isBlank()) {
                String like = "%" + patientQuery.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("patient").get("firstName")), like),
                        cb.like(cb.lower(root.get("patient").get("lastName")), like),
                        cb.like(root.get("patient").get("phone"), "%" + patientQuery.trim() + "%")
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Citas canceladas con >=24h cuyo espacio todavía no se libera (y cuya fecha aún no
     * pasa): capacidad que recepción/doctor debería liberar para que otro paciente la tome. */
    public static Specification<Appointment> pendingRelease(LocalDate today) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), AppointmentStatus.CANCELLED),
                cb.isFalse(root.get("slotReleased")),
                cb.isTrue(root.get("releaseEligible")),
                cb.greaterThanOrEqualTo(root.get("appointmentDate"), today));
    }

    /** Citas ya atendidas que todavía no se cobran (sin pago, o con una transferencia SPEI
     * pendiente): lo que recepción tiene por cobrar. */
    public static Specification<Appointment> pendingPayment() {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), AppointmentStatus.COMPLETED),
                root.get("paymentStatus").in(com.hospital.citas.enums.AppointmentPaymentStatus.UNPAID,
                        com.hospital.citas.enums.AppointmentPaymentStatus.PENDING));
    }

    public static Specification<Appointment> statusIn(AppointmentStatus... statuses) {
        return (root, query, cb) -> root.get("status").in((Object[]) statuses);
    }

    /** Cola de cobro sin texto: lo que recepción tiene por cobrar ahora -- citas ya atendidas sin
     * pagar (de cualquier fecha) y citas programadas de HOY sin pagar (el paciente puede estar
     * en el mostrador antes de pasar con el doctor). */
    public static Specification<Appointment> chargeQueue(LocalDate today) {
        return (root, query, cb) -> cb.and(
                root.get("paymentStatus").in(com.hospital.citas.enums.AppointmentPaymentStatus.UNPAID,
                        com.hospital.citas.enums.AppointmentPaymentStatus.PENDING),
                cb.or(
                        cb.equal(root.get("status"), AppointmentStatus.COMPLETED),
                        cb.and(cb.equal(root.get("status"), AppointmentStatus.SCHEDULED),
                                cb.equal(root.get("appointmentDate"), today))));
    }

    /** Restringe a doctores de alguna de estas especialidades -- usado para acotar lo que ve
     * una recepcionista asignada a una o más especialidades (ver AppointmentServiceImpl,
     * compuesto con {@code .and(...)} sobre el resultado de {@link #search}). No se mezcla
     * con el parámetro {@code specialtyId} de {@code search} porque ese es una igualdad
     * simple (un valor elegido en un filtro), mientras que esto es un IN sobre la lista de
     * especialidades asignadas a la recepcionista. */
    public static Specification<Appointment> doctorSpecialtyIn(List<Long> specialtyIds) {
        return (root, query, cb) -> root.get("doctor").get("specialty").get("id").in(specialtyIds);
    }
}
