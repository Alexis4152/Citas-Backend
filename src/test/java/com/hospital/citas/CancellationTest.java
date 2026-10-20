package com.hospital.citas;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.GuestAppointmentRequest;
import com.hospital.citas.dto.request.MedicalInfoRequest;
import com.hospital.citas.dto.response.AppointmentResponse;
import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.Branch;
import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.Specialty;
import com.hospital.citas.enums.BloodType;
import com.hospital.citas.repository.AppointmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Cubre la regla central de cancelación: al cancelar una cita cuyo horario todavía no llega,
 * el espacio vuelve a estar disponible de inmediato (app.appointments.auto-release-on-cancel). */
class CancellationTest extends AbstractIntegrationTest {

    @Autowired
    private AppointmentRepository appointmentRepository;

    private GuestAppointmentRequest guestRequest(Doctor doctor, Branch branch, LocalDate date, LocalTime time) {
        GuestAppointmentRequest req = new GuestAppointmentRequest();
        req.setFirstName("Invitado");
        // Único por llamada: el agendado de invitado reutiliza al paciente con el mismo nombre+teléfono
        // y limita las citas simultáneas por paciente/teléfono, así que los tests no deben compartirlo.
        req.setLastName("Cancelacion-" + randomLetters(8));
        req.setPhone("55" + (10000000 + new java.util.Random().nextInt(89999999)));
        req.setDoctorId(doctor.getId());
        req.setBranchId(branch.getId());
        req.setAppointmentDate(date);
        req.setStartTime(time);
        MedicalInfoRequest info = new MedicalInfoRequest();
        info.setAllergies("Ninguna");
        info.setBloodType(BloodType.O_POSITIVE);
        req.setMedicalInfo(info);
        return req;
    }

    private AppointmentResponse book(Doctor doctor, Branch branch, LocalDate date, LocalTime time) {
        var response = restTemplate.exchange(
                baseUrl("/api/appointments/guest"), HttpMethod.POST,
                new HttpEntity<>(guestRequest(doctor, branch, date, time)),
                new ParameterizedTypeReference<ApiResponse<AppointmentResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().getData();
    }

    private AppointmentResponse cancel(String cancelToken) {
        var response = restTemplate.exchange(
                baseUrl("/api/appointments/cancel/" + cancelToken), HttpMethod.POST,
                new HttpEntity<>(null), new ParameterizedTypeReference<ApiResponse<AppointmentResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().getData();
    }

    @Test
    void cancellingAFutureAppointmentFreesTheSlotImmediately() {
        Branch branch = createBranch("Sede-Cancel24");
        Specialty specialty = createSpecialty("Especialidad-Cancel24");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(5);

        AppointmentResponse booked = book(doctor, branch, date, LocalTime.of(8, 0));
        AppointmentResponse cancelled = cancel(booked.getCancelToken());

        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getReleaseEligible()).isTrue();
        // Regla nueva: el espacio queda libre en cuanto se cancela, sin esperar a recepción.
        assertThat(cancelled.getSlotReleased()).isTrue();
        assertThat(cancelled.getCanRelease()).isFalse();

        // Ya liberado: liberarlo a mano se rechaza.
        String doctorToken = loginAndGetToken(doctor.getUser().getEmail(), "Doctor123!");
        ResponseEntity<String> manualRelease = restTemplate.exchange(
                baseUrl("/api/doctor/appointments/" + booked.getId() + "/release-slot"), HttpMethod.PATCH,
                new HttpEntity<>(authHeaders(doctorToken)), String.class);
        assertThat(manualRelease.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Y otra persona lo puede agendar de inmediato.
        AppointmentResponse rebooked = book(doctor, branch, date, LocalTime.of(8, 0));
        assertThat(rebooked.getStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    void cancellingLessThan24hInAdvanceAlsoFreesTheSlot() {
        Branch branch = createBranch("Sede-CancelLate");
        Specialty specialty = createSpecialty("Especialidad-CancelLate");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(5);

        AppointmentResponse booked = book(doctor, branch, date, LocalTime.of(9, 0));

        // Movemos la cita a menos de 24h en el futuro directamente en BD (bypassa la
        // validación de horario, que no es lo que este test cubre).
        Appointment appointment = appointmentRepository.findById(booked.getId()).orElseThrow();
        LocalDateTime soon = LocalDateTime.now().plusHours(2);
        appointment.setAppointmentDate(soon.toLocalDate());
        appointment.setStartTime(soon.toLocalTime());
        appointmentRepository.save(appointment);

        AppointmentResponse cancelled = cancel(booked.getCancelToken());

        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        // Cancelación tardía: queda registrada como tal (releaseEligible=false), pero el horario
        // todavía no llega, así que se libera para que alguien más lo aproveche.
        assertThat(cancelled.getReleaseEligible()).isFalse();
        assertThat(cancelled.getSlotReleased()).isTrue();
    }
}
