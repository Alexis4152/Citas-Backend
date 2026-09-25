package com.hospital.citas.repository;

import com.hospital.citas.entity.DoctorSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;

public interface DoctorScheduleRepository extends JpaRepository<DoctorSchedule, Long> {
    List<DoctorSchedule> findByDoctor_IdAndIsActiveTrue(Long doctorId);
    List<DoctorSchedule> findByBranch_IdAndIsActiveTrue(Long branchId);
    List<DoctorSchedule> findByDoctor_IdAndDayOfWeekAndIsActiveTrue(Long doctorId, DayOfWeek dayOfWeek);
}
