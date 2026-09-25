package com.hospital.citas.service.impl;

import com.hospital.citas.dto.response.DashboardResponse;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.BranchRepository;
import com.hospital.citas.repository.DoctorRepository;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorRepository doctorRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse get() {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));

        long appointmentsToday = appointmentRepository.countByAppointmentDateAndStatus(today, AppointmentStatus.SCHEDULED);
        long appointmentsThisWeek = appointmentRepository.countByAppointmentDateBetweenAndStatus(
                weekStart, weekEnd, AppointmentStatus.SCHEDULED);
        long cancelledThisWeek = appointmentRepository.countByAppointmentDateBetweenAndStatus(
                weekStart, weekEnd, AppointmentStatus.CANCELLED);

        return DashboardResponse.builder()
                .appointmentsToday(appointmentsToday)
                .appointmentsThisWeek(appointmentsThisWeek)
                .cancelledThisWeek(cancelledThisWeek)
                .activeDoctors(doctorRepository.countByIsActiveTrue())
                .activeBranches(branchRepository.findByIsActiveTrue().size())
                .activeReceptionists(userRepository.findByRole_NameAndIsActiveTrue(RoleName.RECEPTIONIST).size())
                .build();
    }
}
