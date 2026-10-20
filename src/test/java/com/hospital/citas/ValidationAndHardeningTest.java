package com.hospital.citas;

import com.hospital.citas.enums.RoleName;
import com.hospital.citas.security.CredentialEncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Validaciones de campo y entre campos, headers de seguridad y cifrado de credenciales. */
class ValidationAndHardeningTest extends AbstractIntegrationTest {

    @Autowired
    private CredentialEncryptionService encryption;

    private ResponseEntity<String> post(String path, Object body, String token) {
        HttpHeaders headers = token != null ? authHeaders(token) : new HttpHeaders();
        return restTemplate.exchange(baseUrl(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void namesWithDigitsOrSymbolsAreRejected() {
        Map<String, Object> register = new HashMap<>(Map.of("email", "nuevo-" + UUID.randomUUID() + "@correo.com",
                "password", "Paciente123!", "firstName", "Ana<b>", "lastName", "López"));
        ResponseEntity<String> r = post("/api/auth/register", register, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("solo puede tener letras");

        register.put("firstName", "José María");
        register.put("lastName", "O'Connor-Núñez");
        assertThat(post("/api/auth/register", register, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void emailWithoutARealDomainIsRejected() {
        Map<String, Object> register = Map.of("email", "ana@localhost", "password", "Paciente123!",
                "firstName", "Ana", "lastName", "López");
        assertThat(post("/api/auth/register", register, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void branchClosingBeforeOpeningIsRejected() {
        Map<String, Object> branch = Map.of("name", "Sede Rara", "openTime", "18:00", "closeTime", "09:00");
        ResponseEntity<String> r = post("/api/admin/branches", branch, adminToken());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("cierre debe ser posterior");

        Map<String, Object> half = Map.of("name", "Sede Media", "openTime", "08:00");
        assertThat(post("/api/admin/branches", half, adminToken()).getBody()).contains("apertura y la de cierre");
    }

    @Test
    void newPasswordMustDifferFromTheCurrentOne() {
        String email = "cambio-" + UUID.randomUUID() + "@test.com";
        createUser(email, "Actual123!", RoleName.RECEPTIONIST);
        String token = loginAndGetToken(email, "Actual123!");
        ResponseEntity<String> r = post("/api/auth/change-password",
                Map.of("currentPassword", "Actual123!", "newPassword", "Actual123!"), token);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("distinta de la actual");
    }

    @Test
    void aDisabledAccountLooksLikeWrongCredentialsInsteadOfFailing() {
        String email = "inactivo-" + UUID.randomUUID() + "@test.com";
        var user = createUser(email, "Inactivo123!", RoleName.RECEPTIONIST);
        user.setIsActive(false);
        userRepository.save(user);
        for (String password : new String[]{"Inactivo123!", "OtraCualquiera1"}) {
            ResponseEntity<String> r = post("/api/auth/login", Map.of("email", email, "password", password), null);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.getBody()).contains("Credenciales inválidas");
        }
    }

    @Test
    void appointmentsInThePastAreRejectedByValidation() {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Ana");
        body.put("lastName", "Pasado");
        body.put("phone", "5512345678");
        body.put("doctorId", 1);
        body.put("branchId", 1);
        body.put("appointmentDate", LocalDate.now().minusDays(1).toString());
        body.put("startTime", "10:00");
        body.put("medicalInfo", Map.of("allergies", "Ninguna", "bloodType", "OTHER"));
        ResponseEntity<String> r = post("/api/appointments/guest", body, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("no puede ser pasada").contains("especifica cuál");
    }

    @Test
    void responsesCarrySecurityHeadersAndARequestId() {
        ResponseEntity<String> r = restTemplate.getForEntity(baseUrl("/api/public/specialties"), String.class);
        HttpHeaders h = r.getHeaders();
        assertThat(h.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(h.getFirst("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(h.getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void credentialsAreEncryptedAndLegacyPlainValuesStillRead() {
        String stored = encryption.encrypt("sk_live_secreta");
        assertThat(stored).startsWith("enc:v1:").doesNotContain("sk_live_secreta");
        assertThat(encryption.decrypt(stored)).isEqualTo("sk_live_secreta");
        assertThat(encryption.decrypt("valor-en-claro-de-antes")).isEqualTo("valor-en-claro-de-antes");
        assertThat(encryption.decrypt("enc:v1:basura")).isNull();
    }

    @Test
    void anUploadThatIsNotReallyAnImageIsRejected() {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new MockMultipartFile("file", "logo.png", "image/png", "<svg onload=alert(1)>".getBytes())
                .getResource());
        HttpHeaders headers = authHeaders(adminToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> r = restTemplate.exchange(baseUrl("/api/admin/hospital-config/logo"), HttpMethod.PUT,
                new HttpEntity<>(form, headers), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("no es una imagen válida");
    }
}
