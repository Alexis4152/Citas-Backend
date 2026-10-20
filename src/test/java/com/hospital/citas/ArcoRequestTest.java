package com.hospital.citas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.entity.Hospital;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Solicitudes ARCO por hospital: quién puede levantarlas, quién las atiende y el aislamiento. */
class ArcoRequestTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> body(String email, String type, String details) {
        Map<String, Object> b = new HashMap<>();
        b.put("requesterName", "Lucía Pérez");
        b.put("requesterEmail", email);
        b.put("type", type);
        if (details != null) b.put("details", details);
        b.put("acceptPrivacyNotice", true);
        return b;
    }

    private ResponseEntity<String> submit(Map<String, Object> body) {
        return restTemplate.postForEntity(baseUrl("/api/public/arco-requests"), body, String.class);
    }

    private JsonNode json(ResponseEntity<String> r) throws Exception {
        return objectMapper.readTree(r.getBody());
    }

    private String registeredPatientEmail() {
        String email = "lucia-" + UUID.randomUUID() + "@correo.com";
        patientRepository.save(Patient.builder().firstName("Lucía").lastName("Pérez").phone("5512345678").email(email).build());
        return email;
    }

    @Test
    void onlySomeoneTheHospitalKnowsCanFileARequest() {
        ResponseEntity<String> unknown = submit(body("nadie-" + UUID.randomUUID() + "@correo.com", "ACCESS", null));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody()).contains("no corresponde");

        assertThat(submit(body(registeredPatientEmail(), "ACCESS", null)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void crossFieldRulesAreEnforced() {
        String email = registeredPatientEmail();
        Map<String, Object> noPrivacy = body(email, "ACCESS", null);
        noPrivacy.put("acceptPrivacyNotice", false);
        assertThat(submit(noPrivacy).getBody()).contains("aviso de privacidad");

        ResponseEntity<String> rectifyWithoutDetail = submit(body(email, "RECTIFICATION", null));
        assertThat(rectifyWithoutDetail.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rectifyWithoutDetail.getBody()).contains("qué dato");

        Map<String, Object> badName = body(email, "ACCESS", null);
        badName.put("requesterName", "<script>alert(1)</script>");
        assertThat(submit(badName).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void adminResolvesAndExportsTheDataAndCanOnlyResolveOnce() throws Exception {
        String email = registeredPatientEmail();
        JsonNode created = json(submit(body(email, "ACCESS", "Quiero saber qué datos tienen")));
        long id = created.at("/data/id").asLong();
        assertThat(created.at("/data/folio").asText()).startsWith("ARCO-");
        assertThat(created.at("/data/dueDate").asText()).isNotBlank();

        HttpHeaders admin = authHeaders(adminToken());
        JsonNode list = json(restTemplate.exchange(baseUrl("/api/admin/arco-requests?status=PENDING"), HttpMethod.GET,
                new HttpEntity<>(admin), String.class));
        assertThat(list.get("data").findValuesAsText("folio")).contains("ARCO-" + String.format("%06d", id));

        ResponseEntity<String> export = restTemplate.exchange(baseUrl("/api/admin/arco-requests/" + id + "/export"),
                HttpMethod.GET, new HttpEntity<>(admin), String.class);
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        assertThat(json(export).at("/registrosDePaciente/0/correo").asText()).isEqualTo(email);

        Map<String, Object> resolve = Map.of("status", "COMPLETED", "notes", "Se envió el archivo con tus datos personales.");
        ResponseEntity<String> resolved = restTemplate.exchange(baseUrl("/api/admin/arco-requests/" + id + "/resolve"),
                HttpMethod.PUT, new HttpEntity<>(resolve, admin), String.class);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resolved).at("/data/status").asText()).isEqualTo("COMPLETED");

        ResponseEntity<String> again = restTemplate.exchange(baseUrl("/api/admin/arco-requests/" + id + "/resolve"),
                HttpMethod.PUT, new HttpEntity<>(resolve, admin), String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void patientWithAccountFilesAndFollowsTheirOwnRequests() throws Exception {
        String email = "paciente-" + UUID.randomUUID() + "@correo.com";
        User user = createUser(email, "Paciente123!", RoleName.PATIENT);
        createPatientForUser(user);
        HttpHeaders patient = authHeaders(loginAndGetToken(email, "Paciente123!"));

        Map<String, Object> mine = Map.of("type", "OPPOSITION", "details", "No quiero recibir correos de promociones",
                "acceptPrivacyNotice", true);
        ResponseEntity<String> created = restTemplate.exchange(baseUrl("/api/arco-requests"), HttpMethod.POST,
                new HttpEntity<>(mine, patient), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(created).at("/data/requesterEmail").asText()).isEqualTo(email.toLowerCase());

        JsonNode list = json(restTemplate.exchange(baseUrl("/api/arco-requests/mine"), HttpMethod.GET,
                new HttpEntity<>(patient), String.class));
        assertThat(list.get("data")).hasSize(1);

        // Un paciente no puede ver las de todo el hospital.
        assertThat(restTemplate.exchange(baseUrl("/api/admin/arco-requests"), HttpMethod.GET,
                new HttpEntity<>(patient), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherHospitalsAdminNeverSeesTheRequest() throws Exception {
        long id = json(submit(body(registeredPatientEmail(), "CANCELLATION", null))).at("/data/id").asLong();

        String slug = "otro-arco-" + UUID.randomUUID().toString().substring(0, 6);
        Hospital other = TenantContext.callAs(TenantContext.ROOT,
                () -> hospitalRepository.save(Hospital.builder().name("Otro").slug(slug).build()));
        String adminEmail = "admin-" + UUID.randomUUID() + "@otro.com";
        TenantContext.runAs(other.getId(), () -> createUser(adminEmail, "Admin123!", RoleName.ADMIN));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hospital", slug);
        Map<String, String> login = Map.of("email", adminEmail, "password", "Admin123!");
        String token = json(restTemplate.exchange(baseUrl("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(login, headers), String.class)).at("/data/token").asText();
        HttpHeaders otherAdmin = authHeaders(token);
        otherAdmin.set("X-Hospital", slug);

        JsonNode list = json(restTemplate.exchange(baseUrl("/api/admin/arco-requests"), HttpMethod.GET,
                new HttpEntity<>(otherAdmin), String.class));
        assertThat(list.get("data").findValuesAsText("id")).doesNotContain(String.valueOf(id));
        assertThat(restTemplate.exchange(baseUrl("/api/admin/arco-requests/" + id + "/export"), HttpMethod.GET,
                new HttpEntity<>(otherAdmin), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
