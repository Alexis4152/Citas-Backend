package com.hospital.citas.repository;

import com.hospital.citas.entity.DoctorScheduleException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DoctorScheduleExceptionRepository extends JpaRepository<DoctorScheduleException, Long> {
    List<DoctorScheduleException> findByDoctor_IdAndDateBetweenAndIsActiveTrue(
            Long doctorId, LocalDate from, LocalDate to);

    List<DoctorScheduleException> findByDoctor_IdAndIsActiveTrue(Long doctorId);
}
