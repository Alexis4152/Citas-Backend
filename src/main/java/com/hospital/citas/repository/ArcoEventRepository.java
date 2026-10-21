package com.hospital.citas.repository;

import com.hospital.citas.entity.ArcoEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ArcoEventRepository extends JpaRepository<ArcoEvent, Long> {

    @Query("SELECT e FROM ArcoEvent e LEFT JOIN FETCH e.actor WHERE e.arcoRequest.id = :id ORDER BY e.createdAt ASC, e.id ASC")
    List<ArcoEvent> timeline(@Param("id") Long arcoRequestId);
}
