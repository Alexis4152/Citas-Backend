package com.hospital.citas.repository;

import com.hospital.citas.entity.Payment;
import com.hospital.citas.enums.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByAppointment_IdOrderByCreatedAtDesc(Long appointmentId);

    Optional<Payment> findByOpenpayTransactionId(String openpayTransactionId);

    /** Hospital dueño de una transacción de OpenPay (el webhook no lo dice). Usar en ROOT. */
    @Query("select p.hospitalId from Payment p where p.openpayTransactionId = :transactionId")
    List<Long> findHospitalIdsByOpenpayTransactionId(@Param("transactionId") String transactionId);

    /** Cobros de un corte de caja con cita, doctor, paciente y quién cobró ya cargados. */
    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.appointment a
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            JOIN FETCH a.patient
            LEFT JOIN FETCH p.receivedBy
            WHERE p.cashCut.id = :cutId
            ORDER BY p.createdAt
            """)
    List<Payment> findByCashCutWithDetails(@Param("cutId") Long cutId);

    /** Cobros COMPLETADOS o REEMBOLSADOS en un rango (pago anticipado en línea + recepción). */
    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.appointment a
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            JOIN FETCH a.patient
            LEFT JOIN FETCH p.receivedBy
            WHERE p.createdAt >= :from AND p.createdAt < :to
              AND p.status IN (com.hospital.citas.enums.PaymentStatus.COMPLETED, com.hospital.citas.enums.PaymentStatus.REFUNDED)
              AND (:doctorId IS NULL OR d.id = :doctorId)
            ORDER BY p.createdAt DESC
            """)
    List<Payment> findForReport(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                @Param("doctorId") Long doctorId);

    /** Pagos por revisar: cancelaciones a consideración del administrador. */
    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.appointment a
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            JOIN FETCH a.patient
            LEFT JOIN FETCH p.receivedBy
            WHERE p.refundStatus = com.hospital.citas.enums.RefundStatus.REVIEW
            ORDER BY p.updatedAt DESC
            """)
    List<Payment> findPendingRefundReview();

    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.appointment a
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            JOIN FETCH a.patient
            LEFT JOIN FETCH p.receivedBy
            WHERE p.id = :id
            """)
    Optional<Payment> findByIdWithDetails(@Param("id") Long id);

    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.appointment a
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            JOIN FETCH a.patient
            LEFT JOIN FETCH p.receivedBy
            WHERE a.id = :appointmentId
            ORDER BY p.createdAt DESC
            """)
    List<Payment> findByAppointmentWithDetails(@Param("appointmentId") Long appointmentId);

    long countByMethod(PaymentMethod method);
}
