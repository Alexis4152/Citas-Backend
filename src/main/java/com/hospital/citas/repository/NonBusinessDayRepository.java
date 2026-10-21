package com.hospital.citas.repository;

import com.hospital.citas.entity.NonBusinessDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NonBusinessDayRepository extends JpaRepository<NonBusinessDay, LocalDate> {
}
