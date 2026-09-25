package com.hospital.citas.service.impl;

import com.hospital.citas.dto.request.DoctorScheduleExceptionRequest;
import com.hospital.citas.dto.request.DoctorScheduleRequest;
import com.hospital.citas.dto.response.DoctorScheduleExceptionResponse;
import com.hospital.citas.dto.response.DoctorScheduleResponse;
import com.hospital.citas.entity.Branch;
import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.DoctorSchedule;
import com.hospital.citas.entity.DoctorScheduleException;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.DoctorScheduleMapper;
import com.hospital.citas.entity.Appointment;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.BranchRepository;
import com.hospital.citas.repository.DoctorRepository;
import com.hospital.citas.repository.DoctorScheduleExceptionRepository;
import com.hospital.citas.repository.DoctorScheduleRepository;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.DoctorScheduleService;
import com.hospital.citas.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DoctorScheduleServiceImpl implements DoctorScheduleService {

    private final DoctorScheduleRepository doctorScheduleRepository;
    private final DoctorScheduleExceptionRepository doctorScheduleExceptionRepository;
    private final DoctorRepository doctorRepository;
    private final BranchRepository branchRepository;
    private final DoctorScheduleMapper doctorScheduleMapper;
    private final AppointmentRepository appointmentRepository;
    private final NotificationService notificationService;

    @Override
    @Transactional(readOnly = true)
    public List<DoctorScheduleResponse> list(Long doctorId) {
        return doctorScheduleRepository.findByDoctor_IdAndIsActiveTrue(doctorId).stream()
                .map(doctorScheduleMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public DoctorScheduleResponse create(Long doctorId, DoctorScheduleRequest request) {
        Doctor doctor = findDoctor(doctorId);
        Branch branch = branchRepository.findByIdAndIsActiveTrue(request.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Sede no encontrada: " + request.getBranchId()));
        validateScheduleWindow(doctor, branch, request, null);
        DoctorSchedule schedule = DoctorSchedule.builder()
                .doctor(doctor)
                .branch(branch)
                .dayOfWeek(request.getDayOfWeek())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .slotMinutes(request.getSlotMinutes())
                .build();
        schedule.setCreatedBy(SecurityUtils.getCurrentUserOrNull());
        return doctorScheduleMapper.toResponse(doctorScheduleRepository.save(schedule));
    }

    @Override
    @Transactional
    public DoctorScheduleResponse update(Long doctorId, Long scheduleId, DoctorScheduleRequest request) {
        DoctorSchedule schedule = findOwnedSchedule(doctorId, scheduleId);
        Branch branch = branchRepository.findByIdAndIsActiveTrue(request.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Sede no encontrada: " + request.getBranchId()));
        validateScheduleWindow(schedule.getDoctor(), branch, request, scheduleId);
        schedule.setBranch(branch);
        schedule.setDayOfWeek(request.getDayOfWeek());
        schedule.setStartTime(request.getStartTime());
        schedule.setEndTime(request.getEndTime());
        schedule.setSlotMinutes(request.getSlotMinutes());
        schedule.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        DoctorSchedule saved = doctorScheduleRepository.saveAndFlush(schedule);
        // Si el cambio deja citas ya agendadas fuera del nuevo horario, se rechaza (la
        // transacción se revierte) en vez de dejarlas huérfanas sin avisar a nadie.
        assertNoUncoveredAppointments(saved.getDoctor(), "modificar este horario");
        return doctorScheduleMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(Long doctorId, Long scheduleId) {
        DoctorSchedule schedule = findOwnedSchedule(doctorId, scheduleId);
        schedule.setIsActive(false);
        schedule.setDeletedAt(LocalDateTime.now());
        schedule.setDeletedBy(SecurityUtils.getCurrentUserOrNull());
        doctorScheduleRepository.saveAndFlush(schedule);
        assertNoUncoveredAppointments(schedule.getDoctor(), "eliminar este horario");
    }

    @Override
    @Transactional(readOnly = true)
    public List<DoctorScheduleExceptionResponse> listExceptions(Long doctorId) {
        return doctorScheduleExceptionRepository.findByDoctor_IdAndIsActiveTrue(doctorId).stream()
                .map(doctorScheduleMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public DoctorScheduleExceptionResponse createException(Long doctorId, DoctorScheduleExceptionRequest request) {
        Doctor doctor = findDoctor(doctorId);
        if (request.getDate().isBefore(LocalDate.now())) {
            throw new BusinessException("No puedes bloquear una fecha que ya pasó");
        }
        if (!request.isAllDay()) {
            if (request.getStartTime() == null || request.getEndTime() == null) {
                throw new BusinessException("Debe indicar hora de inicio y fin cuando la excepción no es de día completo");
            }
            if (!request.getStartTime().isBefore(request.getEndTime())) {
                throw new BusinessException("La hora de inicio del bloqueo debe ser anterior a la hora de fin");
            }
        }
        for (DoctorScheduleException existing : doctorScheduleExceptionRepository
                .findByDoctor_IdAndDateBetweenAndIsActiveTrue(doctorId, request.getDate(), request.getDate())) {
            boolean sameWindow = Boolean.TRUE.equals(existing.getAllDay())
                    || (!request.isAllDay() && request.getStartTime().equals(existing.getStartTime())
                        && request.getEndTime().equals(existing.getEndTime()));
            if (sameWindow) {
                throw new BusinessException("Ya tienes un bloqueo que cubre ese día/horario");
            }
        }
        DoctorScheduleException exception = DoctorScheduleException.builder()
                .doctor(doctor)
                .date(request.getDate())
                .allDay(request.isAllDay())
                .startTime(request.isAllDay() ? null : request.getStartTime())
                .endTime(request.isAllDay() ? null : request.getEndTime())
                .reason(request.getReason())
                .build();
        exception.setCreatedBy(SecurityUtils.getCurrentUserOrNull());
        DoctorScheduleExceptionResponse response = doctorScheduleMapper.toResponse(doctorScheduleExceptionRepository.save(exception));

        // El bloqueo se guarda siempre (un doctor enfermo necesita cerrar su agenda YA para que
        // nadie más agende), pero si ese día ya tiene citas programadas se avisa a quien tiene
        // que reprogramarlas -- antes esas citas quedaban ahí sin que nadie se enterara.
        long affected = appointmentRepository.countScheduledInWindow(doctorId, request.getDate(),
                request.isAllDay() ? LocalTime.MIN : request.getStartTime(),
                request.isAllDay() ? LocalTime.of(23, 59, 59) : request.getEndTime());
        response.setAffectedAppointments(affected);
        if (affected > 0) {
            notificationService.notifyScheduleConflict(doctor, request.getDate(), affected);
        }
        return response;
    }

    @Override
    @Transactional
    public void deleteException(Long doctorId, Long exceptionId) {
        DoctorScheduleException exception = doctorScheduleExceptionRepository.findById(exceptionId)
                .filter(e -> e.getDoctor().getId().equals(doctorId))
                .orElseThrow(() -> new ResourceNotFoundException("Excepción no encontrada: " + exceptionId));
        exception.setIsActive(false);
        exception.setDeletedAt(LocalDateTime.now());
        exception.setDeletedBy(SecurityUtils.getCurrentUserOrNull());
        doctorScheduleExceptionRepository.save(exception);
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM");

    /** Reglas de un horario semanal: inicio < fin, dentro del horario de la sede (si la sede
     * lo tiene capturado) y sin traslaparse con otro horario del mismo doctor ese día -- ni
     * siquiera en otra sede (una persona no está en dos lugares a la vez). */
    private void validateScheduleWindow(Doctor doctor, Branch branch, DoctorScheduleRequest request, Long excludeScheduleId) {
        if (!request.getStartTime().isBefore(request.getEndTime())) {
            throw new BusinessException("La hora de inicio debe ser anterior a la hora de fin");
        }
        LocalTime open = branch.getOpenTime();
        LocalTime close = branch.getCloseTime();
        if (open != null && close != null
                && (request.getStartTime().isBefore(open) || request.getEndTime().isAfter(close))) {
            throw new BusinessException("La sede " + branch.getName() + " abre de " + open.format(TIME_FMT)
                    + " a " + close.format(TIME_FMT) + "; el horario del doctor debe quedar dentro de ese rango");
        }
        for (DoctorSchedule other : doctorScheduleRepository
                .findByDoctor_IdAndDayOfWeekAndIsActiveTrue(doctor.getId(), request.getDayOfWeek())) {
            if (other.getId().equals(excludeScheduleId)) {
                continue;
            }
            if (request.getStartTime().isBefore(other.getEndTime()) && request.getEndTime().isAfter(other.getStartTime())) {
                throw new BusinessException("Ese horario se traslapa con otro del mismo día (sede "
                        + other.getBranch().getName() + ", " + other.getStartTime().format(TIME_FMT)
                        + "–" + other.getEndTime().format(TIME_FMT) + ")");
            }
        }
    }

    /** Toda cita programada futura del doctor debe seguir cabiendo en alguno de sus horarios
     * activos (misma sede, mismo día de la semana, dentro del rango). Si no, se rechaza el cambio. */
    private void assertNoUncoveredAppointments(Doctor doctor, String action) {
        LocalDateTime now = LocalDateTime.now();
        List<Appointment> upcoming = appointmentRepository.findUpcomingScheduledByDoctor(
                doctor.getId(), now.toLocalDate(), now.toLocalTime());
        if (upcoming.isEmpty()) {
            return;
        }
        List<DoctorSchedule> schedules = doctorScheduleRepository.findByDoctor_IdAndIsActiveTrue(doctor.getId());
        List<Appointment> uncovered = upcoming.stream()
                // Un sobrecupo se agendó a propósito fuera de la regla normal; no bloquea cambios.
                .filter(a -> !Boolean.TRUE.equals(a.getOverbooked()))
                .filter(a -> schedules.stream().noneMatch(s ->
                        s.getBranch().getId().equals(a.getBranch().getId())
                                && s.getDayOfWeek() == a.getAppointmentDate().getDayOfWeek()
                                && !a.getStartTime().isBefore(s.getStartTime())
                                && !a.getEndTime().isAfter(s.getEndTime())))
                .sorted(java.util.Comparator.comparing(Appointment::getAppointmentDate).thenComparing(Appointment::getStartTime))
                .toList();
        if (!uncovered.isEmpty()) {
            Appointment first = uncovered.get(0);
            throw new BusinessException("No se puede " + action + ": hay " + uncovered.size()
                    + " cita(s) programada(s) que quedarían fuera del horario (la primera: "
                    + first.getAppointmentDate().format(DATE_FMT) + " " + first.getStartTime().format(TIME_FMT)
                    + "). Reprográmalas o cancélalas primero.");
        }
    }

    private Doctor findDoctor(Long doctorId) {
        return doctorRepository.findByIdAndIsActiveTrue(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor no encontrado: " + doctorId));
    }

    private DoctorSchedule findOwnedSchedule(Long doctorId, Long scheduleId) {
        return doctorScheduleRepository.findById(scheduleId)
                .filter(s -> s.getDoctor().getId().equals(doctorId))
                .orElseThrow(() -> new ResourceNotFoundException("Horario no encontrado: " + scheduleId));
    }
}
