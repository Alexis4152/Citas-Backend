package com.hospital.citas.repository;

import com.hospital.citas.entity.PatientClarification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PatientClarificationRepository extends JpaRepository<PatientClarification, Long> {

    @Query("SELECT c FROM PatientClarification c LEFT JOIN FETCH c.createdBy WHERE c.patient.id = :patientId ORDER BY c.createdAt DESC")
    List<PatientClarification> listForPatient(@Param("patientId") Long patientId);
}
