package com.hospital.citas.repository;

import com.hospital.citas.entity.Prescription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    @Query("""
            SELECT p FROM Prescription p
            JOIN FETCH p.appointment a
            JOIN FETCH a.patient
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            WHERE p.id = :id
            """)
    Optional<Prescription> findByIdWithDetails(@Param("id") Long id);

    @Query("""
            SELECT p FROM Prescription p
            JOIN FETCH p.appointment a
            JOIN FETCH a.patient
            WHERE a.id = :appointmentId
            ORDER BY p.createdAt DESC
            """)
    List<Prescription> findByAppointment_IdWithDetails(@Param("appointmentId") Long appointmentId);

    /** Historial completo de un paciente (todas las citas, todos los doctores que lo hayan
     * atendido) -- usado por recepción/admin en la ficha del paciente; el filtro por
     * especialidad de la recepcionista se aplica después, en el service, porque acá no hay
     * forma limpia de saber si el usuario que pregunta tiene o no esa restricción. */
    @Query("""
            SELECT p FROM Prescription p
            JOIN FETCH p.appointment a
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            WHERE a.patient.id = :patientId
            ORDER BY p.createdAt DESC
            """)
    List<Prescription> findByAppointment_Patient_IdWithDetails(@Param("patientId") Long patientId);

    @Query(value = """
            SELECT p FROM Prescription p
            JOIN FETCH p.appointment a
            JOIN FETCH a.patient pat
            WHERE a.doctor.id = :doctorId
              AND (:patientQuery = '' OR LOWER(CONCAT(pat.firstName, ' ', pat.lastName)) LIKE CONCAT('%', :patientQuery, '%'))
            ORDER BY p.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(p) FROM Prescription p
            JOIN p.appointment a JOIN a.patient pat
            WHERE a.doctor.id = :doctorId
              AND (:patientQuery = '' OR LOWER(CONCAT(pat.firstName, ' ', pat.lastName)) LIKE CONCAT('%', :patientQuery, '%'))
            """)
    Page<Prescription> searchByDoctor(@Param("doctorId") Long doctorId, @Param("patientQuery") String patientQuery, Pageable pageable);
}
