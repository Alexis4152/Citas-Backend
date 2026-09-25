package com.hospital.citas.service.impl;

import com.hospital.citas.dto.response.DayAvailabilityResponse;
import com.hospital.citas.dto.response.SlotResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.DoctorRepository;
import com.hospital.citas.repository.DoctorScheduleExceptionRepository;
import com.hospital.citas.repository.DoctorScheduleRepository;
import com.hospital.citas.service.AvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Calcula la disponibilidad de un doctor combinando su plantilla semanal
 * ({@link DoctorSchedule}), las excepciones puntuales ({@link DoctorScheduleException}) y las
 * citas ya ocupadas. Un espacio (doctor + fecha + rango de horas) está OCUPADO si alguna
 * {@link Appointment} con status distinto de CANCELLED (o CANCELLED con slotReleased=false) se
 * TRASLAPA con él -- no basta con comparar la hora de inicio: si el doctor cambia la duración
 * de sus espacios, una cita vieja de 30 min sigue ocupando el hueco de la mitad.
 */
@Service
@RequiredArgsConstructor
public class AvailabilityServiceImpl implements AvailabilityService {

    private final DoctorRepository doctorRepository;
    private final DoctorScheduleRepository doctorScheduleRepository;
    private final DoctorScheduleExceptionRepository doctorScheduleExceptionRepository;
    private final AppointmentRepository appointmentRepository;

    /** Ningún horario del público (invitado o paciente registrado) se puede elegir si ya pasó
     * o si está a menos de 30 minutos de "ahora" -- no tiene sentido ofrecer un slot que el
     * doctor ya no alcanzaría a atender razonablemente. Recepción/admin no tienen esta
     * anticipación mínima (atienden a quien ya está en el mostrador): solo se les niega un
     * espacio que ya terminó. Usa LocalDateTime.now() sin especificar zona a propósito: la JVM
     * ya arranca fijada a la hora local del hospital (ver HospitalCitasBackendApplication,
     * configurable via APP_TIMEZONE). */
    private static final int MIN_MINUTES_BEFORE_SLOT = 30;

    /** Duración de respaldo si un horario/doctor trae un valor inválido (0 o negativo): sin
     * esto, el ciclo que recorre los espacios no avanzaría nunca. */
    private static final int FALLBACK_SLOT_MINUTES = 30;

    @Override
    @Transactional(readOnly = true)
    public List<DayAvailabilityResponse> getAvailability(Long doctorId, LocalDate from, int days) {
        return getAvailability(doctorId, from, days, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayAvailabilityResponse> getAvailability(Long doctorId, LocalDate from, int days, boolean staffMode) {
        Doctor doctor = doctorRepository.findByIdAndIsActiveTrue(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor no encontrado: " + doctorId));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime earliestBookable = staffMode ? now : now.plusMinutes(MIN_MINUTES_BEFORE_SLOT);
        LocalDate to = from.plusDays(Math.max(days, 1) - 1L);
        List<DoctorScheduleException> exceptions =
                doctorScheduleExceptionRepository.findByDoctor_IdAndDateBetweenAndIsActiveTrue(doctorId, from, to);
        Map<LocalDate, List<Appointment>> occupiedByDate = new HashMap<>();
        for (Appointment appt : appointmentRepository.findOccupiedSlots(doctorId, from, to)) {
            occupiedByDate.computeIfAbsent(appt.getAppointmentDate(), d -> new ArrayList<>()).add(appt);
        }

        List<DayAvailabilityResponse> result = new ArrayList<>();
        for (int i = 0; i < Math.max(days, 1); i++) {
            LocalDate date = from.plusDays(i);
            List<DoctorSchedule> schedules =
                    doctorScheduleRepository.findByDoctor_IdAndDayOfWeekAndIsActiveTrue(doctorId, date.getDayOfWeek());

            List<DoctorScheduleException> dayExceptions = exceptions.stream()
                    .filter(e -> e.getDate().equals(date))
                    .toList();
            boolean blockedAllDay = dayExceptions.stream().anyMatch(e -> Boolean.TRUE.equals(e.getAllDay()));
            List<Appointment> dayOccupied = occupiedByDate.getOrDefault(date, List.of());

            LinkedHashMap<LocalTime, SlotResponse> slots = new LinkedHashMap<>();
            for (DoctorSchedule schedule : schedules) {
                // Una sede dada de baja ya no recibe citas -- no se ofrecen sus espacios.
                if (!Boolean.TRUE.equals(schedule.getBranch().getIsActive())) {
                    continue;
                }
                int slotMinutes = resolveSlotMinutes(doctor, schedule);
                LocalTime cursor = schedule.getStartTime();
                while (fitsWithinSameDay(cursor, slotMinutes, schedule.getEndTime())) {
                    LocalTime slotEnd = cursor.plusMinutes(slotMinutes);
                    final LocalTime slotStart = cursor;
                    if (!withinBranchHours(schedule.getBranch(), cursor, slotEnd)) {
                        cursor = slotEnd;
                        continue;
                    }
                    boolean occupied = dayOccupied.stream()
                            .anyMatch(a -> slotStart.isBefore(a.getEndTime()) && slotEnd.isAfter(a.getStartTime()));
                    boolean blocked = blockedAllDay || isBlockedByPartialException(dayExceptions, cursor, slotEnd);
                    boolean bookableInTime = staffMode
                            ? LocalDateTime.of(date, slotEnd).isAfter(earliestBookable)
                            : !LocalDateTime.of(date, cursor).isBefore(earliestBookable);
                    boolean available = !blocked && !occupied && bookableInTime;
                    boolean overbookable = staffMode && !blocked && occupied && bookableInTime;
                    slots.merge(cursor,
                            SlotResponse.builder().startTime(cursor).endTime(slotEnd)
                                    .available(available).overbookable(overbookable).build(),
                            (existing, incoming) -> existing.isAvailable() ? incoming : existing);
                    cursor = slotEnd;
                }
            }

            List<SlotResponse> sorted = new ArrayList<>(slots.values());
            sorted.sort(Comparator.comparing(SlotResponse::getStartTime));
            result.add(DayAvailabilityResponse.builder().date(date).slots(sorted).build());
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public LocalTime resolveEndTimeOrThrow(Doctor doctor, Branch branch, LocalDate date, LocalTime startTime) {
        return resolveEndTimeOrThrow(doctor, branch, date, startTime, false);
    }

    @Override
    @Transactional(readOnly = true)
    public LocalTime resolveEndTimeOrThrow(Doctor doctor, Branch branch, LocalDate date, LocalTime startTime,
                                            boolean staffMode) {
        // Segunda línea de defensa (la primera es que la grilla de disponibilidad ya no
        // ofrece estos horarios como seleccionables) -- por si llega una solicitud directa a
        // la API con un horario ya pasado o a punto de vencer, agendar o reprogramar
        // (ambos pasan por aquí) debe rechazarla igual.
        if (!staffMode && LocalDateTime.of(date, startTime).isBefore(LocalDateTime.now().plusMinutes(MIN_MINUTES_BEFORE_SLOT))) {
            throw new BusinessException("Ese horario ya pasó o está por vencer -- elige uno con al menos "
                    + MIN_MINUTES_BEFORE_SLOT + " minutos de anticipación");
        }
        List<DoctorSchedule> schedules = doctorScheduleRepository
                .findByDoctor_IdAndDayOfWeekAndIsActiveTrue(doctor.getId(), date.getDayOfWeek())
                .stream()
                .filter(s -> s.getBranch().getId().equals(branch.getId()))
                .toList();

        for (DoctorSchedule schedule : schedules) {
            int slotMinutes = resolveSlotMinutes(doctor, schedule);
            LocalTime cursor = schedule.getStartTime();
            while (fitsWithinSameDay(cursor, slotMinutes, schedule.getEndTime())) {
                LocalTime slotEnd = cursor.plusMinutes(slotMinutes);
                if (cursor.equals(startTime)) {
                    if (!withinBranchHours(branch, cursor, slotEnd)) {
                        throw new BusinessException("La sede no atiende en ese horario");
                    }
                    if (staffMode && !LocalDateTime.of(date, slotEnd).isAfter(LocalDateTime.now())) {
                        throw new BusinessException("Ese horario ya terminó");
                    }
                    List<DoctorScheduleException> exceptions = doctorScheduleExceptionRepository
                            .findByDoctor_IdAndDateBetweenAndIsActiveTrue(doctor.getId(), date, date);
                    boolean blockedAllDay = exceptions.stream().anyMatch(e -> Boolean.TRUE.equals(e.getAllDay()));
                    if (blockedAllDay || isBlockedByPartialException(exceptions, cursor, slotEnd)) {
                        throw new BusinessException("El doctor no tiene disponibilidad en ese horario");
                    }
                    return slotEnd;
                }
                cursor = slotEnd;
            }
        }
        throw new BusinessException("El horario solicitado no corresponde a la agenda del doctor en esa sede");
    }

    /** Un espacio nunca se ofrece fuera del horario de apertura de la sede (si lo tiene capturado),
     * aunque el horario del doctor -o un cambio posterior en la sede- lo rebase. */
    private boolean withinBranchHours(Branch branch, LocalTime start, LocalTime end) {
        LocalTime open = branch.getOpenTime();
        LocalTime close = branch.getCloseTime();
        return open == null || close == null || (!start.isBefore(open) && !end.isAfter(close));
    }

    private int resolveSlotMinutes(Doctor doctor, DoctorSchedule schedule) {
        if (schedule.getSlotMinutes() != null && schedule.getSlotMinutes() > 0) {
            return schedule.getSlotMinutes();
        }
        Integer doctorDefault = doctor.getDefaultSlotMinutes();
        return doctorDefault != null && doctorDefault > 0 ? doctorDefault : FALLBACK_SLOT_MINUTES;
    }

    /** El espacio que empieza en {@code cursor} cabe antes del fin del horario Y termina el
     * mismo día: {@code LocalTime} da la vuelta a las 00:00, así que un espacio 23:30–00:00
     * "terminaría" antes de empezar (y el sistema lo marcaría "no asistió" de inmediato). */
    private boolean fitsWithinSameDay(LocalTime cursor, int slotMinutes, LocalTime scheduleEnd) {
        LocalTime slotEnd = cursor.plusMinutes(slotMinutes);
        return slotEnd.isAfter(cursor) && !slotEnd.isAfter(scheduleEnd);
    }

    private boolean isBlockedByPartialException(List<DoctorScheduleException> exceptions,
                                                 LocalTime slotStart, LocalTime slotEnd) {
        return exceptions.stream()
                .filter(e -> !Boolean.TRUE.equals(e.getAllDay()))
                .filter(e -> e.getStartTime() != null && e.getEndTime() != null)
                .anyMatch(e -> slotStart.isBefore(e.getEndTime()) && slotEnd.isAfter(e.getStartTime()));
    }
}
