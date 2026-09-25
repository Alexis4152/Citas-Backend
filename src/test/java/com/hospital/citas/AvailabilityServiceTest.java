package com.hospital.citas;

import com.hospital.citas.dto.response.DayAvailabilityResponse;
import com.hospital.citas.dto.response.SlotResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.DoctorScheduleExceptionRepository;
import com.hospital.citas.service.AvailabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre directamente la lógica de {@link AvailabilityService} (no solo a través del
 * controlador): que un slot ocupado por una cita activa se excluya, que una excepción de
 * agenda bloquee el día/rango, y que un slot vuelva a estar disponible cuando la cita que lo
 * ocupaba se cancela con slotReleased=true.
 */
class AvailabilityServiceTest extends AbstractIntegrationTest {

    @Autowired
    private AvailabilityService availabilityService;

    @Autowired
    private DoctorScheduleExceptionRepository doctorScheduleExceptionRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Test
    void freshDoctorHasAvailableSlotsOnFullWeekSchedule() {
        Branch branch = createBranch("Sede-Avail");
        Specialty specialty = createSpecialty("Especialidad-Avail");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(3);

        List<DayAvailabilityResponse> availability = availabilityService.getAvailability(doctor.getId(), date, 1);

        assertThat(availability).hasSize(1);
        List<SlotResponse> slots = availability.get(0).getSlots();
        assertThat(slots).isNotEmpty();
        Optional<SlotResponse> firstSlot = slots.stream()
                .filter(s -> s.getStartTime().equals(LocalTime.of(8, 0)))
                .findFirst();
        assertThat(firstSlot).isPresent();
        assertThat(firstSlot.get().isAvailable()).isTrue();
    }

    @Test
    void allDayScheduleExceptionBlocksEveryComputedSlot() {
        Branch branch = createBranch("Sede-Except");
        Specialty specialty = createSpecialty("Especialidad-Except");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(3);

        DoctorScheduleException exception = DoctorScheduleException.builder()
                .doctor(doctor).date(date).allDay(true).reason("Vacaciones").build();
        doctorScheduleExceptionRepository.save(exception);

        List<DayAvailabilityResponse> availability = availabilityService.getAvailability(doctor.getId(), date, 1);
        List<SlotResponse> slots = availability.get(0).getSlots();

        assertThat(slots).isNotEmpty();
        assertThat(slots).allSatisfy(slot -> assertThat(slot.isAvailable()).isFalse());
    }

    @Test
    void partialScheduleExceptionBlocksOnlyOverlappingSlots() {
        Branch branch = createBranch("Sede-Partial");
        Specialty specialty = createSpecialty("Especialidad-Partial");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(3);

        DoctorScheduleException exception = DoctorScheduleException.builder()
                .doctor(doctor).date(date).allDay(false)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0))
                .reason("Junta").build();
        doctorScheduleExceptionRepository.save(exception);

        List<DayAvailabilityResponse> availability = availabilityService.getAvailability(doctor.getId(), date, 1);
        List<SlotResponse> slots = availability.get(0).getSlots();

        SlotResponse blocked = slots.stream().filter(s -> s.getStartTime().equals(LocalTime.of(9, 0))).findFirst().orElseThrow();
        SlotResponse free = slots.stream().filter(s -> s.getStartTime().equals(LocalTime.of(8, 0))).findFirst().orElseThrow();

        assertThat(blocked.isAvailable()).isFalse();
        assertThat(free.isAvailable()).isTrue();
    }

    @Test
    void slotOccupiedByActiveAppointmentIsExcludedAndReincludedAfterSlotReleased() {
        Branch branch = createBranch("Sede-Occ");
        Specialty specialty = createSpecialty("Especialidad-Occ");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(3);
        LocalTime slotTime = LocalTime.of(10, 0);

        Patient patient = Patient.builder().firstName("Guest").lastName("Patient").phone("5551110000").build();
        patient = patientRepository.save(patient);

        Appointment appointment = Appointment.builder()
                .doctor(doctor).branch(branch).patient(patient)
                .appointmentDate(date).startTime(slotTime).endTime(slotTime.plusMinutes(30))
                .status(AppointmentStatus.SCHEDULED)
                .slotReleased(false)
                .cancelToken(UUID.randomUUID())
                .build();
        appointment = appointmentRepository.save(appointment);

        List<SlotResponse> slotsWhileOccupied = availabilityService.getAvailability(doctor.getId(), date, 1).get(0).getSlots();
        SlotResponse occupiedSlot = slotsWhileOccupied.stream()
                .filter(s -> s.getStartTime().equals(slotTime)).findFirst().orElseThrow();
        assertThat(occupiedSlot.isAvailable()).isFalse();

        // Cancelada pero slotReleased=false -> el slot sigue ocupado.
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setSlotReleased(false);
        // La cita tiene @Version: se sigue trabajando con la instancia que devuelve save().
        appointment = appointmentRepository.save(appointment);
        List<SlotResponse> slotsStillBlocked = availabilityService.getAvailability(doctor.getId(), date, 1).get(0).getSlots();
        assertThat(slotsStillBlocked.stream().filter(s -> s.getStartTime().equals(slotTime)).findFirst().orElseThrow().isAvailable())
                .isFalse();

        // slotReleased=true -> el slot vuelve a estar disponible.
        appointment.setSlotReleased(true);
        appointmentRepository.save(appointment);
        List<SlotResponse> slotsReleased = availabilityService.getAvailability(doctor.getId(), date, 1).get(0).getSlots();
        assertThat(slotsReleased.stream().filter(s -> s.getStartTime().equals(slotTime)).findFirst().orElseThrow().isAvailable())
                .isTrue();
    }
}
