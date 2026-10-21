package com.hospital.citas.repository;

import com.hospital.citas.entity.ConsentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {

    @Query("SELECT c FROM ConsentRecord c WHERE c.patient.id = :patientId OR (c.user.id = :userId) ORDER BY c.createdAt DESC")
    List<ConsentRecord> history(@Param("patientId") Long patientId, @Param("userId") Long userId);
}
