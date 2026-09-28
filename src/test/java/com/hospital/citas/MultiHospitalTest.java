package com.hospital.citas;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.LoginRequest;
import com.hospital.citas.dto.response.LoginResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Aislamiento entre hospitales: lo de uno nunca se ve ni se toca desde otro. */
class MultiHospitalTest extends AbstractIntegrationTest {

    /** Otro hospital con una especialidad, un doctor y un ADMIN propios. */
    private record OtherHospital(Hospital hospital, Specialty specialty, Doctor doctor, String adminEmail) {}

    private OtherHospital createOtherHospital() {
        String slug = "otro-" + UUID.randomUUID().toString().substring(0, 8);
        Hospital hospital = TenantContext.callAs(TenantContext.ROOT,
                () -> hospitalRepository.save(Hospital.builder().name("Otro hospital").slug(slug).build()));
        String adminEmail = "admin-" + UUID.randomUUID() + "@otro.com";
        return TenantContext.callAs(hospital.getId(), () -> {
            Branch branch = createBranch("Sede otra");
            Specialty specialty = createSpecialty("Especialidad otra");
            Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
            createUser(adminEmail, "Admin123!", RoleName.ADMIN);
            return new OtherHospital(hospital, specialty, doctor, adminEmail);
        });
    }

    private ResponseEntity<ApiResponse<LoginResponse>> login(String email, String password, String hospitalSlug) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hospital", hospitalSlug);
        return restTemplate.exchange(baseUrl("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(request, headers), new ParameterizedTypeReference<>() {});
    }

    @Test
    void publicCatalogOnlyShowsTheLinkHospital() {
        OtherHospital other = createOtherHospital();

        ResponseEntity<ApiResponse<List<Map<String, Object>>>> fromTest = restTemplate.exchange(
                baseUrl("/api/public/specialties"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(fromTest.getBody().getData()).extracting(m -> m.get("id"))
                .doesNotContain(other.specialty().getId().intValue());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hospital", other.hospital().getSlug());
        ResponseEntity<ApiResponse<List<Map<String, Object>>>> fromOther = restTemplate.exchange(
                baseUrl("/api/public/specialties"), HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        assertThat(fromOther.getBody().getData()).extracting(m -> m.get("id"))
                .containsExactly(other.specialty().getId().intValue());
    }

    @Test
    void unknownHospitalLinkIsNotFound() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hospital", "no-existe");
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/public/hospital-config"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void staffOnlyLogsInFromTheirOwnHospitalLink() {
        OtherHospital other = createOtherHospital();
        assertThat(login(other.adminEmail(), "Admin123!", TEST_HOSPITAL_SLUG).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(other.adminEmail(), "Admin123!", other.hospital().getSlug()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminCannotReadAnotherHospitalsDoctorById() {
        OtherHospital other = createOtherHospital();
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/admin/doctors/" + other.doctor().getId()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(adminToken())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sameEmailCanHaveAnAccountInEachHospital() {
        OtherHospital other = createOtherHospital();
        String email = "paciente-" + UUID.randomUUID() + "@mail.com";
        Map<String, String> body = Map.of("firstName", "Ana", "lastName", "Doble", "email", email,
                "phone", "5512345678", "password", "Paciente123!");
        HttpHeaders otherHeaders = new HttpHeaders();
        otherHeaders.set("X-Hospital", other.hospital().getSlug());

        assertThat(restTemplate.postForEntity(baseUrl("/api/auth/register"), body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(restTemplate.exchange(baseUrl("/api/auth/register"), HttpMethod.POST,
                new HttpEntity<>(body, otherHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
