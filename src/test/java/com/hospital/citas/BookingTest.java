package com.hospital.citas;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.AppointmentRequest;
import com.hospital.citas.dto.request.GuestAppointmentRequest;
import com.hospital.citas.dto.request.MedicalInfoRequest;
import com.hospital.citas.dto.response.AppointmentResponse;
import com.hospital.citas.entity.Branch;
import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.Specialty;
import com.hospital.citas.enums.BloodType;
import com.hospital.citas.enums.RoleName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Cubre el agendado de citas de punta a punta contra la API real: invitado, autenticado,
 * rechazo fuera de horario y doble-reserva concurrente sobre el mismo slot. */
class BookingTest extends AbstractIntegrationTest {

    private MedicalInfoRequest medicalInfo() {
        MedicalInfoRequest info = new MedicalInfoRequest();
        info.setAllergies("Ninguna");
        info.setBloodType(BloodType.O_POSITIVE);
        return info;
    }

    private GuestAppointmentRequest guestRequest(Doctor doctor, Branch branch, LocalDate date, LocalTime time) {
        GuestAppointmentRequest req = new GuestAppointmentRequest();
        req.setFirstName("Invitado");
        // Único por llamada: el agendado de invitado reutiliza al paciente con el mismo nombre+teléfono
        // y limita las citas simultáneas por paciente/teléfono, así que los tests no deben compartirlo.
        req.setLastName("Prueba-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        req.setPhone("55" + (10000000 + new java.util.Random().nextInt(89999999)));
        req.setDoctorId(doctor.getId());
        req.setBranchId(branch.getId());
        req.setAppointmentDate(date);
        req.setStartTime(time);
        req.setReasonForVisit("Consulta general");
        req.setMedicalInfo(medicalInfo());
        return req;
    }

    @Test
    void guestBookingCreatesScheduledAppointmentWithoutUserAccount() {
        Branch branch = createBranch("Sede-Guest");
        Specialty specialty = createSpecialty("Especialidad-Guest");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(5);

        var response = restTemplate.exchange(
                baseUrl("/api/appointments/guest"), HttpMethod.POST,
                new HttpEntity<>(guestRequest(doctor, branch, date, LocalTime.of(8, 0))),
                new ParameterizedTypeReference<ApiResponse<AppointmentResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AppointmentResponse body = response.getBody().getData();
        assertThat(body.getStatus()).isEqualTo("SCHEDULED");
        assertThat(body.getPatient().getFirstName()).isEqualTo("Invitado");
        assertThat(body.getCancelToken()).isNotBlank();
    }

    @Test
    void authenticatedPatientCanBookForSelfAndSeeItInOwnList() {
        Branch branch = createBranch("Sede-Self");
        Specialty specialty = createSpecialty("Especialidad-Self");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(5);

        String email = "patient-self-" + System.nanoTime() + "@test.com";
        var patientUser = createUser(email, "Password123", RoleName.PATIENT);
        createPatientForUser(patientUser);
        String token = loginAndGetToken(email, "Password123");

        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setBranchId(branch.getId());
        req.setAppointmentDate(date);
        req.setStartTime(LocalTime.of(9, 0));
        req.setMedicalInfo(medicalInfo());

        var bookResponse = restTemplate.exchange(
                baseUrl("/api/appointments"), HttpMethod.POST,
                new HttpEntity<>(req, authHeaders(token)),
                new ParameterizedTypeReference<ApiResponse<AppointmentResponse>>() {});
        assertThat(bookResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var listResponse = restTemplate.exchange(
                baseUrl("/api/appointments"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                new ParameterizedTypeReference<ApiResponse<List<AppointmentResponse>>>() {});
        assertThat(listResponse.getBody().getData()).hasSize(1);
        assertThat(listResponse.getBody().getData().get(0).getStartTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void bookingOutsideDoctorScheduleIsRejected() {
        Branch branch = createBranch("Sede-Outside");
        Specialty specialty = createSpecialty("Especialidad-Outside");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(5);

        // El horario de prueba es 08:00-18:00; 22:00 esta fuera de rango.
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/appointments/guest"), HttpMethod.POST,
                new HttpEntity<>(guestRequest(doctor, branch, date, LocalTime.of(22, 0))), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void doubleBookingSameSlotIsRejectedWithConflict() {
        Branch branch = createBranch("Sede-Dup");
        Specialty specialty = createSpecialty("Especialidad-Dup");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(5);
        LocalTime time = LocalTime.of(11, 0);

        ResponseEntity<String> first = restTemplate.exchange(
                baseUrl("/api/appointments/guest"), HttpMethod.POST,
                new HttpEntity<>(guestRequest(doctor, branch, date, time)), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = restTemplate.exchange(
                baseUrl("/api/appointments/guest"), HttpMethod.POST,
                new HttpEntity<>(guestRequest(doctor, branch, date, time)), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * Simula concurrencia real contra un slot ya ocupado: dos requests simultáneos deben
     * serializarse a través del bloqueo pesimista de {@code AppointmentRepository
     * .lockOccupantsForSlot} y ambos deben ser rechazados con 409/SlotUnavailableException.
     * <p>
     * Nota: el caso "primera reserva de un slot totalmente libre" depende además del índice
     * único parcial de Postgres (db/01_schema.sql) como segunda línea de defensa, que H2 no
     * soporta de forma idéntica (no tiene índices parciales) — por eso este test parte de un
     * slot que YA tiene un ocupante, que es precisamente el escenario que el bloqueo
     * pesimista está diseñado para serializar de forma determinista en cualquier motor.
     */
    @Test
    void concurrentDoubleBookingAttemptsOnAnOccupiedSlotAreBothRejected() throws InterruptedException {
        Branch branch = createBranch("Sede-Concurrent");
        Specialty specialty = createSpecialty("Especialidad-Concurrent");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(5);
        LocalTime time = LocalTime.of(12, 0);

        ResponseEntity<String> firstBooking = restTemplate.exchange(
                baseUrl("/api/appointments/guest"), HttpMethod.POST,
                new HttpEntity<>(guestRequest(doctor, branch, date, time)), String.class);
        assertThat(firstBooking.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        int attempts = 4;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflictCount = new AtomicInteger();

        List<Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<String> response = restTemplate.exchange(
                        baseUrl("/api/appointments/guest"), HttpMethod.POST,
                        new HttpEntity<>(guestRequest(doctor, branch, date, time)), String.class);
                if (response.getStatusCode() == HttpStatus.CONFLICT) {
                    conflictCount.incrementAndGet();
                }
                return null;
            });
        }
        List<Future<Void>> futures = new java.util.ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        // Espera a que los 4 hilos estén parados justo antes de golpear la API y los
        // suelta a todos a la vez, para maximizar la probabilidad de una colisión real
        // sobre el mismo slot en vez de ejecutarlos esencialmente en serie.
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        pool.shutdown();
        for (Future<Void> future : futures) {
            assertThatFutureDidNotThrow(future);
        }

        assertThat(conflictCount.get()).isEqualTo(attempts);
    }

    private void assertThatFutureDidNotThrow(Future<Void> future) {
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("La tarea concurrente lanzó una excepción inesperada", e);
        }
    }
}
