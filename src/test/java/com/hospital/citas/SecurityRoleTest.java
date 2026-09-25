package com.hospital.citas;

import com.hospital.citas.enums.RoleName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un rol jamás debe poder ejecutar operaciones fuera de su alcance, ni manipulando la
 * petición HTTP directamente: la autorización se valida en Backend (SecurityConfig +
 * hasRole), no solo ocultando botones en Frontend.
 */
class SecurityRoleTest extends AbstractIntegrationTest {

    @Test
    void patientCannotAccessReceptionEndpoints() {
        String email = "patient-" + System.nanoTime() + "@test.com";
        createUser(email, "Password123", RoleName.PATIENT);
        String token = loginAndGetToken(email, "Password123");

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/reception/appointments"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void receptionistCannotAccessAdminEndpoints() {
        String token = receptionistToken();

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/admin/branches"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void doctorCannotAccessAdminEndpoints() {
        String email = "doctor-" + System.nanoTime() + "@test.com";
        createUser(email, "Password123", RoleName.DOCTOR);
        String token = loginAndGetToken(email, "Password123");

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/admin/branches"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanAccessAdminEndpoints() {
        String token = adminToken();

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/admin/branches"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminEndpointsRejectRequestsWithoutToken() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl("/api/admin/branches"), String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void publicCatalogEndpointsArePermitAllWithoutToken() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl("/api/public/branches"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
