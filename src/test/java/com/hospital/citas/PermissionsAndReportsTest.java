package com.hospital.citas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.entity.Branch;
import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Roles y permisos configurables, reportes y datos personales del paciente. */
class PermissionsAndReportsTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode json(ResponseEntity<String> r) throws Exception {
        return objectMapper.readTree(r.getBody());
    }

    private ResponseEntity<String> call(HttpMethod method, String path, Object body, String token) {
        return restTemplate.exchange(baseUrl(path), method, new HttpEntity<>(body, authHeaders(token)), String.class);
    }

    private String token(User user, String password) {
        return loginAndGetToken(user.getEmail(), password);
    }

    @Test
    void theAdminDecidesWhatReceptionCanDoAndCanMakeExceptionsPerPerson() throws Exception {
        Patient patient = patientRepository.save(Patient.builder().firstName("Permiso").lastName("Prueba").phone("5511122233").build());
        User receptionist = createUser("rec-" + UUID.randomUUID() + "@test.com", "Recepcion123!", RoleName.RECEPTIONIST);
        String reception = token(receptionist, "Recepcion123!");
        String admin = adminToken();
        String path = "/api/reception/patients/" + patient.getId();

        // De fábrica, recepción no abre expedientes.
        ResponseEntity<String> denied = call(HttpMethod.GET, path, null, reception);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).contains("abrir expedientes");
        assertThat(json(call(HttpMethod.GET, "/api/auth/permissions", null, reception)).get("data").toString())
                .doesNotContain("PATIENTS_MODULE").contains("APPOINTMENTS_CREATE");

        // El admin se lo da a todo el rol...
        JsonNode matrix = json(call(HttpMethod.GET, "/api/admin/permissions", null, admin)).get("data");
        List<String> granted = new ArrayList<>();
        matrix.at("/roles/RECEPTIONIST").forEach(n -> granted.add(n.asText()));
        granted.add("PATIENTS_MODULE");
        assertThat(call(HttpMethod.PUT, "/api/admin/permissions/roles/RECEPTIONIST", Map.of("permissions", granted), admin)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, path, null, reception).getStatusCode()).isEqualTo(HttpStatus.OK);

        // ...y se lo quita a esta persona en particular.
        assertThat(call(HttpMethod.PUT, "/api/admin/permissions/users/" + receptionist.getId(),
                Map.of("overrides", Map.of("PATIENTS_MODULE", false)), admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, path, null, reception).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Recepción no puede cambiar permisos.
        assertThat(call(HttpMethod.GET, "/api/admin/permissions", null, reception).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        call(HttpMethod.POST, "/api/admin/permissions/roles/RECEPTIONIST/reset", null, admin);
    }

    @Test
    void withoutTheOverbookPermissionReceptionCannotDoubleBook() {
        String admin = adminToken();
        User receptionist = createUser("rec-" + UUID.randomUUID() + "@test.com", "Recepcion123!", RoleName.RECEPTIONIST);
        call(HttpMethod.PUT, "/api/admin/permissions/users/" + receptionist.getId(),
                Map.of("overrides", Map.of("APPOINTMENTS_OVERBOOK", false)), admin);
        Branch branch = createBranch("Sede-Perm");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Perm"));
        Patient patient = patientRepository.save(Patient.builder().firstName("Sobre").lastName("Cupo").phone("5522233344").build());
        Map<String, Object> body = Map.of("patientId", patient.getId(), "doctorId", doctor.getId(), "branchId", branch.getId(),
                "appointmentDate", LocalDate.now().plusDays(3).toString(), "startTime", "10:00", "overbook", true);
        ResponseEntity<String> r = call(HttpMethod.POST, "/api/reception/appointments", body, token(receptionist, "Recepcion123!"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).contains("sobrecupos");
    }

    @Test
    void reportsShowTheirNumbersAndRespectWhoCanSeeThem() throws Exception {
        Branch branch = createBranch("Sede-Rep");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Rep"));
        Map<String, Object> guest = new HashMap<>(Map.of("firstName", "Reporte", "lastName", "Prueba", "phone", "5533344455",
                "doctorId", doctor.getId(), "branchId", branch.getId(), "appointmentDate", LocalDate.now().plusDays(2).toString(),
                "startTime", "11:00", "medicalInfo", Map.of("allergies", "Ninguna", "bloodType", "O_POSITIVE")));
        guest.put("acceptPrivacyNotice", true);
        assertThat(restTemplate.postForEntity(baseUrl("/api/appointments/guest"), guest, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        String range = "?from=" + LocalDate.now() + "&to=" + LocalDate.now().plusDays(7);

        JsonNode overview = json(call(HttpMethod.GET, "/api/reports/overview" + range + "&doctorId=" + doctor.getId(), null, adminToken())).get("data");
        assertThat(overview.at("/kpis/appointments").asInt()).isEqualTo(1);
        assertThat(overview.at("/kpis/onlineShare").asDouble()).isEqualTo(100.0);
        assertThat(overview.get("financial").asBoolean()).isTrue();
        assertThat(overview.get("daily")).hasSize(8);
        assertThat(overview.at("/byDoctor/0/appointments").asInt()).isEqualTo(1);

        ResponseEntity<byte[]> excel = restTemplate.exchange(baseUrl("/api/reports/export.xlsx" + range), HttpMethod.GET,
                new HttpEntity<>(authHeaders(adminToken())), byte[].class);
        assertThat(excel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(excel.getBody()).isNotEmpty();

        // Recepción no ve reportes de fábrica; el doctor solo ve los suyos.
        assertThat(call(HttpMethod.GET, "/api/reports/overview" + range, null, receptionistToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        Doctor other = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Rep2"));
        JsonNode own = json(call(HttpMethod.GET, "/api/reports/overview" + range + "&doctorId=" + doctor.getId(), null,
                loginAndGetToken(other.getUser().getEmail(), "Doctor123!"))).get("data");
        assertThat(own.at("/kpis/appointments").asInt()).isZero();

        // Un paciente nunca ve reportes.
        String patientEmail = "pac-" + UUID.randomUUID() + "@correo.com";
        createUser(patientEmail, "Paciente123!", RoleName.PATIENT);
        assertThat(call(HttpMethod.GET, "/api/reports/overview" + range, null, loginAndGetToken(patientEmail, "Paciente123!"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void everyReportInTheCatalogOpensAndDownloadsAsCsvExcelAndPdf() throws Exception {
        Branch branch = createBranch("Sede-Catalogo");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Catalogo"));
        Map<String, Object> guest = new HashMap<>(Map.of("firstName", "Catálogo", "lastName", "Reportes", "phone", "5533344466",
                "doctorId", doctor.getId(), "branchId", branch.getId(), "appointmentDate", LocalDate.now().plusDays(2).toString(),
                "startTime", "12:00", "medicalInfo", Map.of("allergies", "Ninguna", "bloodType", "O_POSITIVE")));
        guest.put("acceptPrivacyNotice", true);
        assertThat(restTemplate.postForEntity(baseUrl("/api/appointments/guest"), guest, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        String admin = adminToken();
        String range = "from=" + LocalDate.now() + "&to=" + LocalDate.now().plusDays(7);
        java.nio.file.Path dir = java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/previews/reportes"));

        JsonNode catalog = json(call(HttpMethod.GET, "/api/reports/catalog", null, admin)).get("data");
        assertThat(catalog.size()).isGreaterThanOrEqualTo(14);
        for (JsonNode def : catalog) {
            String key = def.get("key").asText();
            JsonNode table = json(call(HttpMethod.GET, "/api/reports/table/" + key + "?" + range, null, admin)).get("data");
            assertThat(table.get("title").asText()).as(key).isNotBlank();
            assertThat(table.get("columns").size()).as(key).isPositive();
            for (String format : List.of("csv", "xlsx", "pdf")) {
                ResponseEntity<byte[]> file = restTemplate.exchange(baseUrl("/api/reports/table/" + key + "/export?format=" + format + "&" + range),
                        HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), byte[].class);
                assertThat(file.getStatusCode()).as(key + " " + format).isEqualTo(HttpStatus.OK);
                assertThat(file.getBody()).as(key + " " + format).isNotEmpty();
                if (format.equals("pdf")) {
                    try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(file.getBody())) {
                        assertThat(doc.getNumberOfPages()).isPositive();
                    }
                    java.nio.file.Files.write(dir.resolve(key + ".pdf"), file.getBody());
                }
            }
        }
        JsonNode appointments = json(call(HttpMethod.GET, "/api/reports/table/appointments?" + range + "&doctorId=" + doctor.getId(), null, admin)).get("data");
        assertThat(appointments.get("rows")).hasSize(1);
        assertThat(appointments.get("rows").get(0).toString()).contains("Catálogo Reportes", "En línea");

        // Recepción no ve reportes de fábrica; un doctor no ve los de dinero; nadie sin permiso descarga.
        assertThat(call(HttpMethod.GET, "/api/reports/table/appointments?" + range, null, receptionistToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        String doctorToken = loginAndGetToken(doctor.getUser().getEmail(), "Doctor123!");
        String own = json(call(HttpMethod.GET, "/api/reports/catalog", null, doctorToken)).get("data").toString();
        assertThat(own).contains("\"appointments\"");
        assertThat(call(HttpMethod.GET, "/api/reports/table/appointments/export?format=pdf&" + range, null, doctorToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/reports/table/no-existe?" + range, null, admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Un corte de caja también se baja con su detalle.
        JsonNode cut = json(call(HttpMethod.POST, "/api/reception/cash-cut/open", Map.of("openingAmount", 500), admin)).get("data");
        ResponseEntity<byte[]> cutPdf = restTemplate.exchange(baseUrl("/api/reception/cash-cut/" + cut.get("id").asLong() + "/export?format=pdf"),
                HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), byte[].class);
        assertThat(cutPdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        java.nio.file.Files.write(dir.resolve("corte.pdf"), cutPdf.getBody());
        call(HttpMethod.POST, "/api/reception/cash-cut/" + cut.get("id").asLong() + "/close", Map.of("countedCash", 500), admin);
    }

    @Test
    void patientPersonalDataIsValidatedAndSaved() throws Exception {
        Patient patient = patientRepository.save(Patient.builder().firstName("Datos").lastName("Personales").phone("5544455566").build());
        String admin = adminToken();
        Map<String, Object> body = new HashMap<>(Map.of("firstName", "Datos", "lastName", "Personales", "phone", "5544455566",
                "postalCode", "123", "birthDate", LocalDate.now().minusYears(30).toString()));
        ResponseEntity<String> bad = call(HttpMethod.PUT, "/api/reception/patients/" + patient.getId() + "/profile", body, admin);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).contains("código postal");

        body.put("postalCode", "06700");
        body.put("sex", "F");
        body.put("addressLine", "Av. Insurgentes 123");
        body.put("emergencyContactName", "Laura Pérez");
        ResponseEntity<String> missingPhone = call(HttpMethod.PUT, "/api/reception/patients/" + patient.getId() + "/profile", body, admin);
        assertThat(missingPhone.getBody()).contains("contacto de emergencia");

        body.put("emergencyContactPhone", "5599988877");
        JsonNode saved = json(call(HttpMethod.PUT, "/api/reception/patients/" + patient.getId() + "/profile", body, admin)).get("data");
        assertThat(saved.get("postalCode").asText()).isEqualTo("06700");
        assertThat(saved.get("age").asInt()).isEqualTo(30);
        assertThat(saved.get("sex").asText()).isEqualTo("F");
    }
}
