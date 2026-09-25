package com.hospital.citas;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.GuestAppointmentRequest;
import com.hospital.citas.dto.request.MedicalInfoRequest;
import com.hospital.citas.dto.response.AppointmentResponse;
import com.hospital.citas.dto.response.DoctorScheduleExceptionResponse;
import com.hospital.citas.dto.response.PrescriptionResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.BloodType;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.service.AvailabilityService;
import com.hospital.citas.service.impl.AppointmentServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Huecos funcionales cerrados en la revisión de agenda/citas: validación de agenda, empalmes,
 * cambios que dejan citas huérfanas, sobrecupo, llegada del paciente, no-show automático,
 * límites por paciente, reprogramación del propio paciente, fusión de pacientes y recetas
 * anulables. */
class FunctionalGapsTest extends AbstractIntegrationTest {

    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private AvailabilityService availabilityService;
    @Autowired
    private AppointmentServiceImpl appointmentService;

    // ── Helpers ──────────────────────────────────────────────────

    private GuestAppointmentRequest guest(Doctor doctor, Branch branch, LocalDate date, LocalTime time,
                                          String first, String last, String phone) {
        GuestAppointmentRequest req = new GuestAppointmentRequest();
        req.setFirstName(first);
        req.setLastName(last);
        req.setPhone(phone);
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

    private String randomPhone() {
        return "55" + (10000000 + new java.util.Random().nextInt(89999999));
    }

    private GuestAppointmentRequest anonymousGuest(Doctor doctor, Branch branch, LocalDate date, LocalTime time) {
        return guest(doctor, branch, date, time, "Invitado", "Gap-" + UUID.randomUUID().toString().substring(0, 8), randomPhone());
    }

    private ResponseEntity<ApiResponse<AppointmentResponse>> postGuest(GuestAppointmentRequest req) {
        return restTemplate.exchange(baseUrl("/api/appointments/guest"), HttpMethod.POST, new HttpEntity<>(req),
                new ParameterizedTypeReference<>() {});
    }

    private AppointmentResponse bookGuestOk(GuestAppointmentRequest req) {
        var response = postGuest(req);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().getData();
    }

    private String doctorToken(Doctor doctor) {
        return loginAndGetToken(doctor.getUser().getEmail(), "Doctor123!");
    }

    private ResponseEntity<String> call(HttpMethod method, String path, Object body, String token) {
        return restTemplate.exchange(baseUrl(path), method,
                new HttpEntity<>(body, token == null ? null : authHeaders(token)), String.class);
    }

    private Patient newPatient(String first, String last, String phone) {
        return patientRepository.save(Patient.builder().firstName(first).lastName(last).phone(phone).build());
    }

    /** Cita insertada directo en BD (para escenarios con fecha/hora "ahora" que la API rechazaría). */
    private Appointment insertAppointment(Doctor doctor, Branch branch, Patient patient, LocalDateTime start,
                                          int minutes, AppointmentStatus status) {
        return appointmentRepository.save(Appointment.builder()
                .doctor(doctor).branch(branch).patient(patient)
                .appointmentDate(start.toLocalDate()).startTime(start.toLocalTime())
                .endTime(start.toLocalTime().plusMinutes(minutes))
                .status(status).slotReleased(false).cancelToken(UUID.randomUUID())
                .build());
    }

    /** Doctor con horario las 24 horas (00:00–23:30) todos los días, para probar "ahora" sin
     * depender de la hora a la que corran las pruebas. */
    private Doctor createAllDayDoctor(Branch branch, Specialty specialty) {
        User doctorUser = createUser("doctor-" + UUID.randomUUID() + "@test.com", "Doctor123!", com.hospital.citas.enums.RoleName.DOCTOR);
        Doctor doctor = doctorRepository.save(Doctor.builder().user(doctorUser).specialty(specialty)
                .licenseNumber("CED-ALL").defaultSlotMinutes(30).branches(List.of(branch)).build());
        for (DayOfWeek day : DayOfWeek.values()) {
            doctorScheduleRepository.save(DoctorSchedule.builder().doctor(doctor).branch(branch).dayOfWeek(day)
                    .startTime(LocalTime.of(0, 0)).endTime(LocalTime.of(23, 30)).slotMinutes(30).build());
        }
        return doctor;
    }

    private Branch openAllDayBranch() {
        Branch branch = createBranch("Sede-Gap");
        branch.setOpenTime(null);
        branch.setCloseTime(null);
        return branchRepository.save(branch);
    }

    // ── 1 · Validación de la agenda del doctor ──────────────────

    @Test
    void scheduleWithZeroSlotMinutesIsRejected() {
        Branch branch = createBranch("Sede-Slot0");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Slot0"));
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/doctor/schedule",
                Map.of("branchId", branch.getId(), "dayOfWeek", "MONDAY", "startTime", "06:00", "endTime", "07:00", "slotMinutes", 0),
                doctorToken(doctor));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("al menos 5 minutos");
    }

    @Test
    void scheduleOutsideBranchHoursOrOverlappingAnotherIsRejected() {
        Branch branch = createBranch("Sede-Hours"); // abre 08:00–20:00
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Hours"));
        String token = doctorToken(doctor);

        ResponseEntity<String> outside = call(HttpMethod.POST, "/api/doctor/schedule",
                Map.of("branchId", branch.getId(), "dayOfWeek", "MONDAY", "startTime", "06:00", "endTime", "07:00"), token);
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(outside.getBody()).contains("abre de");

        ResponseEntity<String> overlapping = call(HttpMethod.POST, "/api/doctor/schedule",
                Map.of("branchId", branch.getId(), "dayOfWeek", "MONDAY", "startTime", "09:00", "endTime", "10:00"), token);
        assertThat(overlapping.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overlapping.getBody()).contains("se traslapa");
    }

    @Test
    void changingBranchHoursClampsDoctorSchedulesAndIsRejectedWhenItStrandsAppointments() {
        Branch branch = createBranch("Sede-Cierre"); // 08:00–20:00; el doctor de prueba trabaja 08:00–18:00
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Cierre"));
        String admin = adminToken();
        Map<String, Object> body = Map.of("name", branch.getName(), "openTime", "08:00", "closeTime", "16:00");

        // La sede ahora cierra a las 16:00: los horarios del doctor (hasta 18:00) se recortan a 16:00.
        assertThat(call(HttpMethod.PUT, "/api/admin/branches/" + branch.getId(), body, admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(doctorScheduleRepository.findByDoctor_IdAndIsActiveTrue(doctor.getId()))
                .allSatisfy(sc -> assertThat(sc.getEndTime()).isEqualTo(LocalTime.of(16, 0)));

        // Hay una cita a las 15:30 (dentro de 16:00): acortar el cierre a las 15:00 la dejaría fuera -> se rechaza.
        bookGuestOk(anonymousGuest(doctor, branch, LocalDate.now().plusDays(5), LocalTime.of(15, 30)));
        ResponseEntity<String> tooEarly = call(HttpMethod.PUT, "/api/admin/branches/" + branch.getId(),
                Map.of("name", branch.getName(), "openTime", "08:00", "closeTime", "15:00"), admin);
        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooEarly.getBody()).contains("fuera del nuevo horario");

        // Apertura posterior al cierre no tiene sentido.
        assertThat(call(HttpMethod.PUT, "/api/admin/branches/" + branch.getId(),
                Map.of("name", branch.getName(), "openTime", "18:00", "closeTime", "09:00"), admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── 3 · Empalmes por rango, no solo por hora de inicio ──────

    @Test
    void appointmentLongerThanSlotOccupiesTheOverlappingSlotsToo() {
        Branch branch = createBranch("Sede-Overlap");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Overlap"));
        LocalDate date = LocalDate.now().plusDays(3);
        insertAppointment(doctor, branch, newPatient("Larga", "Cita", randomPhone()),
                LocalDateTime.of(date, LocalTime.of(9, 0)), 45, AppointmentStatus.SCHEDULED);

        var slots = availabilityService.getAvailability(doctor.getId(), date, 1).get(0).getSlots();
        assertThat(slots.stream().filter(s -> s.getStartTime().equals(LocalTime.of(9, 0))).findFirst().orElseThrow().isAvailable()).isFalse();
        assertThat(slots.stream().filter(s -> s.getStartTime().equals(LocalTime.of(9, 30))).findFirst().orElseThrow().isAvailable()).isFalse();
        assertThat(slots.stream().filter(s -> s.getStartTime().equals(LocalTime.of(10, 0))).findFirst().orElseThrow().isAvailable()).isTrue();
    }

    // ── 2 · Cambios de agenda con citas ya agendadas ────────────

    @Test
    void deletingScheduleThatCoversAnAppointmentIsRejectedAndExceptionReportsAffected() {
        Branch branch = createBranch("Sede-Orphan");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Orphan"));
        LocalDate date = LocalDate.now().plusDays(5);
        bookGuestOk(anonymousGuest(doctor, branch, date, LocalTime.of(10, 0)));
        String token = doctorToken(doctor);

        DoctorSchedule schedule = doctorScheduleRepository
                .findByDoctor_IdAndDayOfWeekAndIsActiveTrue(doctor.getId(), date.getDayOfWeek()).get(0);
        ResponseEntity<String> delete = call(HttpMethod.DELETE, "/api/doctor/schedule/" + schedule.getId(), null, token);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(delete.getBody()).contains("Reprográmalas");
        // Nada cambió: el horario sigue activo.
        assertThat(doctorScheduleRepository.findById(schedule.getId()).orElseThrow().getIsActive()).isTrue();

        // Un bloqueo de agenda SÍ se guarda, pero informa cuántas citas quedaron afectadas.
        var exception = restTemplate.exchange(baseUrl("/api/doctor/schedule-exceptions"), HttpMethod.POST,
                new HttpEntity<>(Map.of("date", date.toString(), "allDay", true, "reason", "Congreso"), authHeaders(token)),
                new ParameterizedTypeReference<ApiResponse<DoctorScheduleExceptionResponse>>() {});
        assertThat(exception.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(exception.getBody().getData().getAffectedAppointments()).isEqualTo(1L);
    }

    @Test
    void deactivatingDoctorBranchOrSpecialtyWithUpcomingAppointmentsIsRejected() {
        Branch branch = createBranch("Sede-Baja");
        Specialty specialty = createSpecialty("Esp-Baja");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        bookGuestOk(anonymousGuest(doctor, branch, LocalDate.now().plusDays(5), LocalTime.of(10, 0)));
        String admin = adminToken();

        for (String path : List.of("/api/admin/doctors/" + doctor.getId(), "/api/admin/branches/" + branch.getId(),
                "/api/admin/specialties/" + specialty.getId())) {
            ResponseEntity<String> response = call(HttpMethod.DELETE, path, null, admin);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("cita(s) programada(s)");
        }
    }

    // ── 4 · Privacidad de la búsqueda de citas de invitado ──────

    @Test
    void guestLookupNeverReturnsAppointmentsOrTokens() {
        Branch branch = createBranch("Sede-Lookup");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Lookup"));
        String phone = randomPhone();
        var booked = bookGuestOk(guest(doctor, branch, LocalDate.now().plusDays(5), LocalTime.of(10, 0), "Ana", "Lookup", phone));

        ResponseEntity<String> found = call(HttpMethod.POST, "/api/appointments/guest-lookup",
                Map.of("phone", phone, "firstName", "Ana", "lastName", "Lookup"), null);
        ResponseEntity<String> notFound = call(HttpMethod.POST, "/api/appointments/guest-lookup",
                Map.of("phone", randomPhone(), "firstName", "Nadie", "lastName", "Existe"), null);

        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).doesNotContain(booked.getCancelToken());
        // Misma respuesta exista o no la cita: no confirma a un tercero que el paciente existe.
        assertThat(found.getBody()).isEqualTo(notFound.getBody());
    }

    @Test
    void guestWithoutEmailCanFindTheirAppointmentWithExactDateAndTime() {
        Branch branch = createBranch("Sede-NoMail");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-NoMail"));
        String phone = randomPhone();
        LocalDate date = LocalDate.now().plusDays(5);
        var booked = bookGuestOk(guest(doctor, branch, date, LocalTime.of(10, 0), "Sin", "Correo", phone));

        var found = restTemplate.exchange(baseUrl("/api/appointments/guest-lookup"), HttpMethod.POST,
                new HttpEntity<>(Map.of("phone", phone, "firstName", "Sin", "lastName", "Correo",
                        "appointmentDate", date.toString(), "startTime", "10:00")),
                new ParameterizedTypeReference<ApiResponse<AppointmentResponse>>() {});
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody().getData().getCancelToken()).isEqualTo(booked.getCancelToken());
        // Sin datos sensibles: ni motivo de consulta ni información médica del paciente.
        assertThat(found.getBody().getData().getReasonForVisit()).isNull();
        assertThat(found.getBody().getData().getPatient()).isNull();

        // Con una hora equivocada no hay coincidencia.
        ResponseEntity<String> wrong = call(HttpMethod.POST, "/api/appointments/guest-lookup",
                Map.of("phone", phone, "firstName", "Sin", "lastName", "Correo",
                        "appointmentDate", date.toString(), "startTime", "11:00"), null);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void patientCannotCancelAnAppointmentThatAlreadyStarted() {
        Branch branch = createBranch("Sede-Started");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Started"));
        Appointment started = insertAppointment(doctor, branch, newPatient("Ya", "Empezo", randomPhone()),
                LocalDateTime.now().minusMinutes(5), 30, AppointmentStatus.SCHEDULED);

        ResponseEntity<String> response = call(HttpMethod.POST, "/api/appointments/cancel/" + started.getCancelToken(), Map.of(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("ya inició");
    }

    // ── 5 · No-show con gracia, llegada, corrección, walk-in y sobrecupo ──

    @Test
    void autoNoShowRespectsGraceAndArrivalAndCanBeCorrectedToCompleted() {
        Branch branch = createBranch("Sede-NoShow");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-NoShow"));
        LocalDateTime longAgo = LocalDateTime.now().minusHours(3);

        Appointment missed = insertAppointment(doctor, branch, newPatient("No", "Llego", randomPhone()), longAgo, 30, AppointmentStatus.SCHEDULED);
        Appointment arrived = insertAppointment(doctor, branch, newPatient("Si", "Llego", randomPhone()), longAgo.plusHours(1), 30, AppointmentStatus.SCHEDULED);
        arrived.setArrivedAt(LocalDateTime.now().minusHours(2));
        arrived = appointmentRepository.save(arrived);
        // Terminó hace 5 minutos: dentro del margen de gracia (15) -- todavía no es no-show.
        Appointment withinGrace = insertAppointment(doctor, branch, newPatient("Casi", "Tarde", randomPhone()),
                LocalDateTime.now().minusMinutes(35), 30, AppointmentStatus.SCHEDULED);

        appointmentService.autoMarkPastDueAsNoShow();

        assertThat(appointmentRepository.findById(missed.getId()).orElseThrow().getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
        assertThat(appointmentRepository.findById(arrived.getId()).orElseThrow().getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(appointmentRepository.findById(withinGrace.getId()).orElseThrow().getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);

        // Recepción corrige el "no asistió" a "atendida" (antes solo se podía emitiendo una receta).
        ResponseEntity<String> corrected = call(HttpMethod.PATCH, "/api/reception/appointments/" + missed.getId() + "/complete", null, receptionistToken());
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(appointmentRepository.findById(missed.getId()).orElseThrow().getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    void receptionCanRegisterArrivalAndThenCompleteBeforeTheScheduledTime() {
        Branch branch = openAllDayBranch();
        Doctor doctor = createAllDayDoctor(branch, createSpecialty("Esp-Arrive"));
        LocalDateTime later = LocalDateTime.now().plusHours(2);
        assumeTrue(later.toLocalDate().equals(LocalDate.now()));
        Appointment appt = insertAppointment(doctor, branch, newPatient("Llega", "Temprano", randomPhone()), later, 30, AppointmentStatus.SCHEDULED);
        String reception = receptionistToken();

        // Sin llegada registrada no se puede marcar antes de la hora...
        assertThat(call(HttpMethod.PATCH, "/api/reception/appointments/" + appt.getId() + "/complete", null, reception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // ...con la llegada registrada, sí.
        assertThat(call(HttpMethod.PATCH, "/api/reception/appointments/" + appt.getId() + "/arrived", null, reception).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PATCH, "/api/reception/appointments/" + appt.getId() + "/complete", null, reception).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void receptionCanBookTheSlotInProgressForAWalkInButThePublicCannot() {
        LocalTime nowTime = LocalTime.now();
        assumeTrue(nowTime.isAfter(LocalTime.of(0, 5)) && nowTime.isBefore(LocalTime.of(23, 25)));
        Branch branch = openAllDayBranch();
        Doctor doctor = createAllDayDoctor(branch, createSpecialty("Esp-Walkin"));
        LocalTime slotStart = LocalTime.of(nowTime.getHour(), nowTime.getMinute() < 30 ? 0 : 30);
        Patient walkIn = newPatient("Sin", "Cita", randomPhone());

        ResponseEntity<ApiResponse<AppointmentResponse>> publicAttempt = postGuest(anonymousGuest(doctor, branch, LocalDate.now(), slotStart));
        assertThat(publicAttempt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var staff = restTemplate.exchange(baseUrl("/api/reception/appointments"), HttpMethod.POST,
                new HttpEntity<>(Map.of("patientId", walkIn.getId(), "doctorId", doctor.getId(), "branchId", branch.getId(),
                        "appointmentDate", LocalDate.now().toString(), "startTime", slotStart.toString()), authHeaders(receptionistToken())),
                new ParameterizedTypeReference<ApiResponse<AppointmentResponse>>() {});
        assertThat(staff.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // El paciente ya está en el mostrador: su llegada queda registrada (el job no la marca no-show).
        assertThat(staff.getBody().getData().getArrivedAt()).isNotNull();
    }

    @Test
    void receptionCanOverbookAnOccupiedSlotButOthersCannot() {
        Branch branch = createBranch("Sede-Overbook");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Overbook"));
        LocalDate date = LocalDate.now().plusDays(5);
        bookGuestOk(anonymousGuest(doctor, branch, date, LocalTime.of(10, 0)));
        Patient urgent = newPatient("Urgencia", "Sobrecupo", randomPhone());
        String reception = receptionistToken();
        Map<String, Object> plain = Map.of("patientId", urgent.getId(), "doctorId", doctor.getId(), "branchId", branch.getId(),
                "appointmentDate", date.toString(), "startTime", "10:00");
        Map<String, Object> overbook = Map.of("patientId", urgent.getId(), "doctorId", doctor.getId(), "branchId", branch.getId(),
                "appointmentDate", date.toString(), "startTime", "10:00", "overbook", true);

        assertThat(call(HttpMethod.POST, "/api/reception/appointments", plain, reception).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var extra = restTemplate.exchange(baseUrl("/api/reception/appointments"), HttpMethod.POST,
                new HttpEntity<>(overbook, authHeaders(reception)), new ParameterizedTypeReference<ApiResponse<AppointmentResponse>>() {});
        assertThat(extra.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(extra.getBody().getData().getOverbooked()).isTrue();

        var slot = availabilityService.getAvailability(doctor.getId(), date, 1, true).get(0).getSlots().stream()
                .filter(s -> s.getStartTime().equals(LocalTime.of(10, 0))).findFirst().orElseThrow();
        assertThat(slot.isAvailable()).isFalse();
        assertThat(slot.isOverbookable()).isTrue();
    }

    // ── 8/9 · Pacientes: reutilizar invitado, no duplicar citas, topes ──

    @Test
    void sameGuestReusesPatientAndCannotBeInTwoPlacesAtOnce() {
        Branch branch = createBranch("Sede-Same");
        Specialty specialty = createSpecialty("Esp-Same");
        Doctor doctorA = createDoctorWithFullWeekSchedule(branch, specialty);
        Doctor doctorB = createDoctorWithFullWeekSchedule(branch, specialty);
        LocalDate date = LocalDate.now().plusDays(5);
        String phone = randomPhone();
        String last = "Mismo-" + UUID.randomUUID().toString().substring(0, 6);

        var first = bookGuestOk(guest(doctorA, branch, date, LocalTime.of(10, 0), "Luis", last, phone));
        var second = bookGuestOk(guest(doctorA, branch, date, LocalTime.of(11, 0), "Luis", last, phone));
        assertThat(second.getPatient().getId()).isEqualTo(first.getPatient().getId());

        // Misma persona, otro doctor, misma hora que su primera cita.
        var overlap = postGuest(guest(doctorB, branch, date, LocalTime.of(10, 0), "Luis", last, phone));
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overlap.getBody().getMessage()).contains("otra cita");
    }

    @Test
    void guestCannotHoardMoreThanTheMaximumOfUpcomingAppointments() {
        Branch branch = createBranch("Sede-Limit");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Limit"));
        LocalDate date = LocalDate.now().plusDays(6);
        String phone = randomPhone();
        String last = "Acaparador-" + UUID.randomUUID().toString().substring(0, 6);

        for (int i = 0; i < 5; i++) {
            bookGuestOk(guest(doctor, branch, date, LocalTime.of(8, 0).plusMinutes(30L * i), "Ivan", last, phone));
        }
        var sixth = postGuest(guest(doctor, branch, date, LocalTime.of(11, 0), "Ivan", last, phone));
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(sixth.getBody().getMessage()).contains("máximo");
    }

    @Test
    void mergingPatientsMovesAppointmentsAndDeactivatesTheSource() {
        Branch branch = createBranch("Sede-Merge");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Merge"));
        Patient source = newPatient("Dup", "Origen", randomPhone());
        Patient target = newPatient("Dup", "Destino", randomPhone());
        LocalDate date = LocalDate.now().plusDays(4);
        insertAppointment(doctor, branch, source, LocalDateTime.of(date, LocalTime.of(9, 0)), 30, AppointmentStatus.SCHEDULED);
        insertAppointment(doctor, branch, target, LocalDateTime.of(date, LocalTime.of(10, 0)), 30, AppointmentStatus.SCHEDULED);

        ResponseEntity<String> response = call(HttpMethod.POST,
                "/api/reception/patients/" + source.getId() + "/merge-into/" + target.getId(), null, receptionistToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(appointmentRepository.findByPatient_IdOrderByAppointmentDateDescStartTimeDesc(target.getId())).hasSize(2);
        assertThat(appointmentRepository.findByPatient_IdOrderByAppointmentDateDescStartTimeDesc(source.getId())).isEmpty();
        assertThat(patientRepository.findById(source.getId()).orElseThrow().getIsActive()).isFalse();
    }

    // ── 7 · Recordatorio y reprogramación ───────────────────────

    @Test
    void reschedulingResetsReminderAndPatientCanSelfRescheduleWithEnoughNotice() {
        Branch branch = createBranch("Sede-Resched");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Resched"));
        LocalDate date = LocalDate.now().plusDays(5);
        var booked = bookGuestOk(anonymousGuest(doctor, branch, date, LocalTime.of(10, 0)));

        Appointment stored = appointmentRepository.findById(booked.getId()).orElseThrow();
        stored.setReminder2hSentAt(LocalDateTime.now());
        appointmentRepository.save(stored);

        // Recepción reprograma: el recordatorio de la fecha nueva vuelve a estar pendiente.
        ResponseEntity<String> byStaff = call(HttpMethod.PATCH, "/api/reception/appointments/" + booked.getId() + "/reschedule",
                Map.of("appointmentDate", date.plusDays(1).toString(), "startTime", "11:00"), receptionistToken());
        assertThat(byStaff.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(appointmentRepository.findById(booked.getId()).orElseThrow().getReminder2hSentAt()).isNull();

        // El propio invitado reprograma con su token (faltan más de 24 horas).
        ResponseEntity<String> byGuest = call(HttpMethod.POST, "/api/appointments/reschedule/" + booked.getCancelToken(),
                Map.of("appointmentDate", date.plusDays(2).toString(), "startTime", "12:00"), null);
        assertThat(byGuest.getStatusCode()).isEqualTo(HttpStatus.OK);
        Appointment moved = appointmentRepository.findById(booked.getId()).orElseThrow();
        assertThat(moved.getAppointmentDate()).isEqualTo(date.plusDays(2));
        assertThat(moved.getStartTime()).isEqualTo(LocalTime.of(12, 0));

        // Con menos de 24 horas de anticipación ya no puede por su cuenta.
        Appointment soon = insertAppointment(doctor, branch, newPatient("Casi", "Hoy", randomPhone()),
                LocalDateTime.now().plusHours(3), 30, AppointmentStatus.SCHEDULED);
        ResponseEntity<String> tooLate = call(HttpMethod.POST, "/api/appointments/reschedule/" + soon.getCancelToken(),
                Map.of("appointmentDate", date.plusDays(3).toString(), "startTime", "12:00"), null);
        assertThat(tooLate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLate.getBody()).contains("horas de anticipación");
    }

    // ── 10 · Acciones simultáneas sobre la misma cita ───────────

    @Test
    void secondStaleUpdateOfTheSameAppointmentIsRejectedInsteadOfOverwriting() {
        Branch branch = createBranch("Sede-Version");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Version"));
        Appointment saved = insertAppointment(doctor, branch, newPatient("Doble", "Accion", randomPhone()),
                LocalDateTime.now().plusDays(2), 30, AppointmentStatus.SCHEDULED);

        Appointment viewOfReception = appointmentRepository.findById(saved.getId()).orElseThrow();
        Appointment viewOfDoctor = appointmentRepository.findById(saved.getId()).orElseThrow();
        viewOfReception.setStatus(AppointmentStatus.CANCELLED);
        appointmentRepository.save(viewOfReception);

        viewOfDoctor.setStatus(AppointmentStatus.COMPLETED);
        assertThatThrownBy(() -> appointmentRepository.save(viewOfDoctor)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(appointmentRepository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    // ── 11 · Recetas anulables, sin duplicados por descuido ─────

    @Test
    void prescriptionsCanBeVoidedAndDuplicatesNeedConfirmation() {
        Branch branch = createBranch("Sede-Rx");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Rx"));
        Appointment appt = insertAppointment(doctor, branch, newPatient("Con", "Receta", randomPhone()),
                LocalDateTime.now().minusMinutes(20), 30, AppointmentStatus.SCHEDULED);
        String token = doctorToken(doctor);

        var first = restTemplate.exchange(baseUrl("/api/doctor/prescriptions"), HttpMethod.POST,
                new HttpEntity<>(Map.of("appointmentId", appt.getId(), "content", "Paracetamol 500 mg"), authHeaders(token)),
                new ParameterizedTypeReference<ApiResponse<PrescriptionResponse>>() {});
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long firstId = first.getBody().getData().getId();

        // Una segunda receta sobre la misma cita exige confirmación explícita.
        ResponseEntity<String> duplicate = call(HttpMethod.POST, "/api/doctor/prescriptions",
                Map.of("appointmentId", appt.getId(), "content", "Paracetamol 500 mg"), token);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(duplicate.getBody()).contains("receta vigente");

        // Anular exige motivo; con motivo queda marcada como anulada (no se borra).
        assertThat(call(HttpMethod.POST, "/api/doctor/prescriptions/" + firstId + "/void", Map.of("reason", ""), token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var voided = restTemplate.exchange(baseUrl("/api/doctor/prescriptions/" + firstId + "/void"), HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Dosis mal capturada"), authHeaders(token)),
                new ParameterizedTypeReference<ApiResponse<PrescriptionResponse>>() {});
        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().getData().isVoided()).isTrue();

        // Una receta anulada no se manda por correo, y como ya no hay vigente se puede emitir la corregida sin confirmar.
        ResponseEntity<String> email = call(HttpMethod.POST, "/api/doctor/prescriptions/" + firstId + "/send-email", null, token);
        assertThat(email.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(email.getBody()).contains("anulada");
        assertThat(call(HttpMethod.POST, "/api/doctor/prescriptions",
                Map.of("appointmentId", appt.getId(), "content", "Paracetamol 250 mg"), token).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }
}
