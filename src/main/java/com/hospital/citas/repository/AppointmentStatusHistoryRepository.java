package com.hospital.citas.repository;

import com.hospital.citas.entity.AppointmentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentStatusHistoryRepository extends JpaRepository<AppointmentStatusHistory, Long> {
}
