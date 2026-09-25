package com.hospital.citas.repository;

import com.hospital.citas.entity.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    Optional<Patient> findByUser_Id(Long userId);

    /** Búsqueda de pacientes por texto libre (teléfono/nombre/apellido/correo) y, opcionalmente,
     * solo los que tienen (o tuvieron) al menos una cita con cierto doctor y/o de cierta
     * especialidad -- el mismo criterio doctor/especialidad que los filtros de Citas. */
    @Query("""
            SELECT p FROM Patient p
            WHERE p.isActive = true
              AND (p.phone LIKE CONCAT('%', :q, '%')
                   OR LOWER(p.firstName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(p.lastName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(p.email) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:doctorId IS NULL OR EXISTS (
                    SELECT 1 FROM Appointment a WHERE a.patient = p AND a.doctor.id = :doctorId))
              AND (:specialtyId IS NULL OR EXISTS (
                    SELECT 1 FROM Appointment a WHERE a.patient = p AND a.doctor.specialty.id = :specialtyId))
            """)
    Page<Patient> search(@Param("q") String q, @Param("doctorId") Long doctorId,
                         @Param("specialtyId") Long specialtyId, Pageable pageable);

    List<Patient> findByIsActiveTrue();

    /** Paciente de invitado (sin cuenta) ya registrado con el mismo teléfono + nombre +
     * apellido: se reutiliza en vez de crear un registro nuevo en cada cita. */
    Optional<Patient> findFirstByUserIsNullAndIsActiveTrueAndPhoneAndFirstNameIgnoreCaseAndLastNameIgnoreCaseOrderByIdAsc(
            String phone, String firstName, String lastName);

    /** Posibles duplicados de un paciente: mismo teléfono o mismo correo (ver
     * PatientServiceImpl#findDuplicates). Una casa suele compartir teléfono, así que esto solo
     * SUGIERE -- fusionar siempre lo confirma una persona. */
    @Query("""
            SELECT p FROM Patient p
            WHERE p.id <> :id AND p.isActive = true
              AND ((:phone <> '' AND p.phone = :phone)
                   OR (:email <> '' AND p.email IS NOT NULL AND LOWER(p.email) = LOWER(:email)))
            ORDER BY p.id
            """)
    List<Patient> findPossibleDuplicates(@Param("id") Long id, @Param("phone") String phone, @Param("email") String email);
}
