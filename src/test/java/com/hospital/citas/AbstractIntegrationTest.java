package com.hospital.citas;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.LoginRequest;
import com.hospital.citas.dto.response.LoginResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Base de las pruebas de integración: app real (puerto aleatorio) + H2, con helpers para
 * crear usuarios/sedes/especialidades/doctores/pacientes de prueba y autenticarse. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(FakePaymentGatewayConfig.class)
public abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected RoleRepository roleRepository;

    @Autowired
    protected BranchRepository branchRepository;

    @Autowired
    protected SpecialtyRepository specialtyRepository;

    @Autowired
    protected DoctorRepository doctorRepository;

    @Autowired
    protected DoctorScheduleRepository doctorScheduleRepository;

    @Autowired
    protected PatientRepository patientRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @BeforeEach
    void ensureRoles() {
        // data.sql ya las siembra, pero por si un test corre en un contexto sin recargar.
        for (RoleName roleName : RoleName.values()) {
            if (roleRepository.findByName(roleName).isEmpty()) {
                roleRepository.save(Role.builder().name(roleName).build());
            }
        }
    }

    protected String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }

    protected User createUser(String email, String rawPassword, RoleName roleName) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .firstName("Test")
                .lastName(roleName.name())
                .role(role)
                .build();
        return userRepository.save(user);
    }

    protected String loginAndGetToken(String email, String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        ResponseEntity<ApiResponse<LoginResponse>> response = restTemplate.exchange(
                baseUrl("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(request),
                new ParameterizedTypeReference<>() {}
        );
        return response.getBody().getData().getToken();
    }

    protected String adminToken() {
        String email = "admin-" + UUID.randomUUID() + "@test.com";
        createUser(email, "Admin123!", RoleName.ADMIN);
        return loginAndGetToken(email, "Admin123!");
    }

    protected String receptionistToken() {
        String email = "reception-" + UUID.randomUUID() + "@test.com";
        createUser(email, "Reception123!", RoleName.RECEPTIONIST);
        return loginAndGetToken(email, "Reception123!");
    }

    protected HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    protected Branch createBranch(String name) {
        Branch branch = Branch.builder()
                .name(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                .address("Calle de prueba 123")
                .city("CDMX")
                .phone("5550000000")
                .openTime(LocalTime.of(8, 0))
                .closeTime(LocalTime.of(20, 0))
                .build();
        return branchRepository.save(branch);
    }

    protected Specialty createSpecialty(String name) {
        Specialty specialty = Specialty.builder()
                .name(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                .description("Especialidad de prueba")
                .build();
        return specialtyRepository.save(specialty);
    }

    /** Doctor con horario TODOS los días 08:00-18:00, slots de 30 min, en la sede dada
     * (para que los tests de disponibilidad no dependan del día de la semana en que corren). */
    protected Doctor createDoctorWithFullWeekSchedule(Branch branch, Specialty specialty) {
        String email = "doctor-" + UUID.randomUUID() + "@test.com";
        User doctorUser = createUser(email, "Doctor123!", RoleName.DOCTOR);
        Doctor doctor = Doctor.builder()
                .user(doctorUser)
                .specialty(specialty)
                .licenseNumber("CED-TEST")
                .defaultSlotMinutes(30)
                .branches(List.of(branch))
                .build();
        doctor = doctorRepository.save(doctor);

        for (DayOfWeek day : DayOfWeek.values()) {
            DoctorSchedule schedule = DoctorSchedule.builder()
                    .doctor(doctor)
                    .branch(branch)
                    .dayOfWeek(day)
                    .startTime(LocalTime.of(8, 0))
                    .endTime(LocalTime.of(18, 0))
                    .slotMinutes(30)
                    .build();
            doctorScheduleRepository.save(schedule);
        }
        return doctor;
    }

    protected Patient createPatientForUser(User user) {
        Patient patient = Patient.builder()
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone("5551234567")
                .email(user.getEmail())
                .user(user)
                .build();
        return patientRepository.save(patient);
    }
}
