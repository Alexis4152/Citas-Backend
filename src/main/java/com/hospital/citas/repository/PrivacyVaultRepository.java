package com.hospital.citas.repository;

import com.hospital.citas.entity.PrivacyVault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PrivacyVaultRepository extends JpaRepository<PrivacyVault, Long> {

    Optional<PrivacyVault> findFirstByPatient_IdAndPurgedAtIsNullOrderByIdDesc(Long patientId);

    @Query("SELECT v FROM PrivacyVault v JOIN FETCH v.patient WHERE v.purgedAt IS NULL AND v.retainUntil < :today")
    List<PrivacyVault> findExpired(@Param("today") LocalDate today);
}
