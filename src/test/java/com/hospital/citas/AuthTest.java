package com.hospital.citas;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.LoginRequest;
import com.hospital.citas.dto.request.RegisterRequest;
import com.hospital.citas.dto.response.LoginResponse;
import com.hospital.citas.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTest extends AbstractIntegrationTest {

    @Test
    void registerCreatesUserRoleAlwaysPatient() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("nuevo-" + System.nanoTime() + "@test.com");
        req.setPassword("Password123");
        req.setFirstName("Nuevo");
        req.setLastName("Paciente");

        ResponseEntity<ApiResponse<LoginResponse>> response = restTemplate.exchange(
                baseUrl("/api/auth/register"), HttpMethod.POST, new HttpEntity<>(req),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getData().getUser().getRole()).isEqualTo("PATIENT");

        User saved = userRepository.findByEmail(req.getEmail()).orElseThrow();
        assertThat(saved.getRole().getName().name()).isEqualTo("PATIENT");
        // La contraseña nunca se guarda en texto plano.
        assertThat(saved.getPasswordHash()).isNotEqualTo(req.getPassword());
        // El registro publico siempre crea tambien la fila de paciente ligada a la cuenta.
        assertThat(patientRepository.findByUser_Id(saved.getId())).isPresent();
    }

    @Test
    void registerRejectsDuplicateEmail() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("duplicado-" + System.nanoTime() + "@test.com");
        req.setPassword("Password123");
        req.setFirstName("A");
        req.setLastName("B");

        restTemplate.postForEntity(baseUrl("/api/auth/register"), req, ApiResponse.class);
        ResponseEntity<ApiResponse> second = restTemplate.postForEntity(
                baseUrl("/api/auth/register"), req, ApiResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void loginSucceedsWithValidCredentials() {
        String email = "login-ok-" + System.nanoTime() + "@test.com";
        createUser(email, "Secret123!", com.hospital.citas.enums.RoleName.PATIENT);

        String token = loginAndGetToken(email, "Secret123!");

        assertThat(token).isNotBlank();
    }

    @Test
    void loginFailsWithWrongPassword() {
        String email = "login-fail-" + System.nanoTime() + "@test.com";
        createUser(email, "Correct123!", com.hospital.citas.enums.RoleName.PATIENT);

        LoginRequest req = new LoginRequest();
        req.setEmail(email);
        req.setPassword("WrongPassword");

        ResponseEntity<ApiResponse> response = restTemplate.postForEntity(
                baseUrl("/api/auth/login"), req, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Protección contra fuerza bruta (ver AuthServiceImpl#login): al quinto intento fallido
     * consecutivo la cuenta se bloquea temporalmente, incluso si el siguiente intento ya trae
     * la contraseña correcta. */
    @Test
    void loginLocksAccountAfterTooManyFailedAttempts() {
        String email = "login-lock-" + System.nanoTime() + "@test.com";
        createUser(email, "Correct123!", com.hospital.citas.enums.RoleName.PATIENT);

        LoginRequest wrong = new LoginRequest();
        wrong.setEmail(email);
        wrong.setPassword("WrongPassword");

        for (int i = 0; i < 5; i++) {
            ResponseEntity<ApiResponse> response = restTemplate.postForEntity(
                    baseUrl("/api/auth/login"), wrong, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        LoginRequest correct = new LoginRequest();
        correct.setEmail(email);
        correct.setPassword("Correct123!");

        ResponseEntity<ApiResponse> lockedResponse = restTemplate.postForEntity(
                baseUrl("/api/auth/login"), correct, ApiResponse.class);

        assertThat(lockedResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(lockedResponse.getBody().getMessage()).contains("bloqueada");
    }
}
