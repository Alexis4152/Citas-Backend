package com.hospital.citas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.AppointmentPaymentStatus;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.repository.*;
import com.hospital.citas.security.CredentialEncryptionService;
import com.hospital.citas.service.ArcoRequestService;
import com.hospital.citas.service.BusinessCalendar;
import com.hospital.citas.service.PrivacyService;
import com.hospital.citas.tenant.TenantContext;
import com.hospital.citas.util.SecureTokens;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Derechos ARCO de punta a punta: correo único por hospital, confirmación de correo,
 * verificación de identidad, plazos, respuesta, ejecución (reporte, bloqueo, anonimización),
 * centro de privacidad del paciente y aislamiento entre hospitales.
 */
class ArcoRequestTest extends AbstractIntegrationTest {

    @Autowired private ObjectMapper objectMapper;
    @Autowired private ArcoRequestRepository arcoRequestRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private PrivacyVaultRepository vaultRepository;
    @Autowired private ConsentRecordRepository consentRecordRepository;
    @Autowired private EmailTokenRepository emailTokenRepository;
    @Autowired private NonBusinessDayRepository nonBusinessDayRepository;
    @Autowired private CredentialEncryptionService encryption;
    @Autowired private ArcoRequestService arcoRequestService;
    @Autowired private PrivacyService privacyService;

    // ── Ayudas ──────────────────────────────────────────────────

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

    private ResponseEntity<String> admin(HttpMethod method, String path, Object body) {
        return restTemplate.exchange(baseUrl(path), method, new HttpEntity<>(body, authHeaders(adminToken())), String.class);
    }

    private Patient patient(String first, String last, String email) {
        return patientRepository.save(Patient.builder().firstName(first).lastName(last).phone("55" + (10000000 + new Random().nextInt(89999999)))
                .email(email).build());
    }

    /** El enlace de seguimiento solo viaja por correo: en la prueba se lee del registro (cifrado). */
    private String trackingToken(long arcoId) {
        return encryption.decrypt(arcoRequestRepository.findById(arcoId).orElseThrow().getTrackingTokenEnc());
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    }

    private ResponseEntity<String> uploadIdentity(String token) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(pngBytes()) {
            @Override
            public String getFilename() {
                return "ine.png";
            }
        });
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(baseUrl("/api/public/arco/track/" + token + "/identity"), HttpMethod.POST, new HttpEntity<>(form, h), String.class);
    }

    private Appointment appointment(Patient patient, LocalDate date, AppointmentStatus status, String reason) {
        Branch branch = createBranch("Sede-ARCO");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-ARCO"));
        return appointmentRepository.save(Appointment.builder().doctor(doctor).branch(branch).patient(patient)
                .appointmentDate(date).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
                .status(status).reasonForVisit(reason).slotReleased(false).cancelToken(UUID.randomUUID()).overbooked(false)
                .price(new BigDecimal("500.00")).paymentStatus(AppointmentPaymentStatus.UNPAID).build());
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    // ── Un correo = un paciente por hospital ────────────────────

    @Test
    void anEmailBelongsToOnePatientPerHospitalButCanRepeatInAnotherHospital() throws Exception {
        String email = "unico-" + UUID.randomUUID() + "@correo.com";
        Map<String, Object> first = Map.of("firstName", "Ana", "lastName", "Única", "phone", "5511112222", "email", email,
                "medicalInfo", Map.of("allergies", "Ninguna", "bloodType", "O_POSITIVE"));
        assertThat(restTemplate.exchange(baseUrl("/api/reception/patients"), HttpMethod.POST,
                new HttpEntity<>(first, authHeaders(receptionistToken())), String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> second = Map.of("firstName", "Beto", "lastName", "Otro", "phone", "5533334444", "email", email,
                "medicalInfo", Map.of("allergies", "Ninguna", "bloodType", "O_POSITIVE"));
        ResponseEntity<String> dup = restTemplate.exchange(baseUrl("/api/reception/patients"), HttpMethod.POST,
                new HttpEntity<>(second, authHeaders(receptionistToken())), String.class);
        assertThat(dup.getStatusCode().is4xxClientError()).isTrue();
        assertThat(dup.getBody()).contains("ya pertenece a otro paciente");

        // Agendar como invitado con ese correo a nombre de otra persona tampoco se permite...
        Branch branch = createBranch("Sede-Unico");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Unico"));
        Map<String, Object> guest = new HashMap<>(Map.of("firstName", "Carla", "lastName", "Ajena", "phone", "5544445555", "email", email,
                "doctorId", doctor.getId(), "branchId", branch.getId(), "appointmentDate", LocalDate.now().plusDays(3).toString(),
                "startTime", "10:00", "medicalInfo", Map.of("allergies", "Ninguna", "bloodType", "O_POSITIVE")));
        guest.put("acceptPrivacyNotice", true);
        ResponseEntity<String> foreign = restTemplate.postForEntity(baseUrl("/api/appointments/guest"), guest, String.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(foreign.getBody()).contains("otra persona");

        // ...pero la misma persona sí reutiliza su expediente (y queda la constancia de consentimiento).
        guest.put("firstName", "Ana");
        guest.put("lastName", "Única");
        assertThat(restTemplate.postForEntity(baseUrl("/api/appointments/guest"), guest, String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(patientRepository.findByEmailIgnoreCase(email)).hasSize(1);
        Patient ana = patientRepository.findFirstByEmailIgnoreCaseAndIsActiveTrue(email).orElseThrow();
        assertThat(consentRecordRepository.history(ana.getId(), -1L)).extracting(ConsentRecord::getScope)
                .contains(ConsentRecord.NOTICE, ConsentRecord.SENSITIVE);

        // En otro hospital el mismo correo es otro paciente.
        Hospital other = TenantContext.callAs(TenantContext.ROOT,
                () -> hospitalRepository.save(Hospital.builder().name("Otro").slug("otro-" + UUID.randomUUID().toString().substring(0, 6)).build()));
        Patient there = TenantContext.callAs(other.getId(), () -> patient("Ana", "Única", email));
        assertThat(there.getId()).isNotEqualTo(ana.getId());
    }

    @Test
    void registeringWithTheEmailOfAnExistingRecordNeedsTheActivationLink() throws Exception {
        String email = "expediente-" + UUID.randomUUID() + "@correo.com";
        Patient guestRecord = patient("Rosa", "Previa", email);
        Map<String, Object> register = Map.of("email", email, "password", "Paciente123!", "firstName", "Rosa", "lastName", "Previa",
                "phone", "5512121212", "acceptPrivacyNotice", true);
        ResponseEntity<String> r = restTemplate.postForEntity(baseUrl("/api/auth/register"), register, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(json(r).at("/data/pendingActivation").asBoolean()).isTrue();
        assertThat(restTemplate.postForEntity(baseUrl("/api/auth/login"), Map.of("email", email, "password", "Paciente123!"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // El enlace del correo: se conoce su valor reemplazando su hash por el de un token conocido.
        EmailToken t = emailTokenRepository.findAll().stream().filter(x -> x.getPatient() != null && x.getPatient().getId().equals(guestRecord.getId()))
                .findFirst().orElseThrow();
        t.setTokenHash(SecureTokens.hash("token-de-prueba-" + t.getId()));
        emailTokenRepository.save(t);
        ResponseEntity<String> activated = restTemplate.postForEntity(baseUrl("/api/auth/activate/token-de-prueba-" + t.getId()), null, String.class);
        assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patientRepository.findById(guestRecord.getId()).orElseThrow().getUser()).isNotNull();
        assertThat(loginAndGetToken(email, "Paciente123!")).isNotBlank();
        // El enlace es de un solo uso.
        assertThat(restTemplate.postForEntity(baseUrl("/api/auth/activate/token-de-prueba-" + t.getId()), null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Flujo público completo: Acceso ──────────────────────────

    @Test
    void accessRequestNeedsEmailConfirmationAndIdentityAndDeliversOnlyTheTitularsData() throws Exception {
        String email = "lucia-" + UUID.randomUUID() + "@correo.com";
        Patient lucia = patient("Lucía", "Pérez", email);
        appointment(lucia, LocalDate.now().minusDays(10), AppointmentStatus.COMPLETED, "Dolor de cabeza");
        Patient stranger = patient("Ernesto", "Ajeno", null);
        appointment(stranger, LocalDate.now().minusDays(5), AppointmentStatus.COMPLETED, "Otra cosa");

        // Un correo que no es de nadie recibe la misma respuesta y no crea nada (no revela quién es paciente).
        String nobody = "nadie-" + UUID.randomUUID() + "@correo.com";
        ResponseEntity<String> unknown = submit(body(nobody, "ACCESS", null));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(unknown).at("/data/folio").isMissingNode() || json(unknown).at("/data/folio").isNull()).isTrue();
        assertThat(arcoRequestRepository.findAll()).noneMatch(a -> nobody.equals(a.getRequesterEmail()));
        ResponseEntity<String> real = submit(body(email, "ACCESS", "Quiero saber qué datos tienen de mí"));
        assertThat(json(real).at("/data/folio").isMissingNode() || json(real).at("/data/folio").isNull()).isTrue();
        long id = arcoRequestRepository.findAll().stream().filter(a -> email.equals(a.getRequesterEmail()))
                .mapToLong(a -> a.getId()).max().orElseThrow();
        assertThat(json(admin(HttpMethod.GET, "/api/admin/arco-requests", null)).get("data").findValuesAsText("id"))
                .as("sin confirmar el correo no aparece en la bandeja").doesNotContain(String.valueOf(id));

        String token = trackingToken(id);
        JsonNode confirmed = json(restTemplate.postForEntity(baseUrl("/api/public/arco/track/" + token + "/confirm"), null, String.class));
        assertThat(confirmed.at("/data/request/status").asText()).isEqualTo("PENDING");
        assertThat(confirmed.at("/data/request/nextDeadline").asText()).isNotBlank();

        Map<String, Object> procede = Map.of("outcome", "PROCEDENTE", "notes", "Te entregamos tus datos.");
        ResponseEntity<String> tooSoon = admin(HttpMethod.POST, "/api/admin/arco-requests/" + id + "/respond", procede);
        assertThat(tooSoon.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooSoon.getBody()).contains("verifica la identidad");

        assertThat(uploadIdentity(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = json(admin(HttpMethod.GET, "/api/admin/arco-requests/" + id, null)).get("data");
        long identityDoc = detail.get("documents").get(0).get("id").asLong();
        assertThat(admin(HttpMethod.GET, "/api/admin/arco-requests/" + id + "/documents/" + identityDoc, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin(HttpMethod.POST, "/api/admin/arco-requests/" + id + "/verify-identity",
                Map.of("method", "ID_ONLINE", "notes", "INE vigente, coincide nombre")).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode answered = json(admin(HttpMethod.POST, "/api/admin/arco-requests/" + id + "/respond", procede));
        assertThat(answered.at("/data/status").asText()).isEqualTo("APPROVED");
        assertThat(answered.at("/data/executionDue").asText()).isNotBlank();
        JsonNode executed = json(admin(HttpMethod.POST, "/api/admin/arco-requests/" + id + "/execute", Map.of()));
        assertThat(executed.at("/data/status").asText()).isEqualTo("COMPLETED");

        // El titular baja su reporte desde su enlace: trae SUS datos y nada de otras personas.
        JsonNode portal = json(restTemplate.getForEntity(baseUrl("/api/public/arco/track/" + token), String.class)).get("data");
        long delivery = 0;
        for (JsonNode d : portal.get("documents")) if (d.get("kind").asText().equals("DELIVERY")) delivery = d.get("id").asLong();
        ResponseEntity<byte[]> report = restTemplate.getForEntity(baseUrl("/api/public/arco/track/" + token + "/documents/" + delivery), byte[].class);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = pdfText(report.getBody());
        assertThat(text).contains("Lucía Pérez", "Dolor de cabeza").doesNotContain("Ernesto", "Otra cosa");
        // La identificación nunca se descarga desde el portal público.
        assertThat(restTemplate.getForEntity(baseUrl("/api/public/arco/track/" + token + "/documents/" + identityDoc), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin(HttpMethod.GET, "/api/admin/arco-requests/" + id + "/constancia", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Cancelación: bloqueo y anonimización ────────────────────

    @Test
    void cancellationErasesTheAccountBlocksWhatTheLawKeepsAndAnonymizesWhenItExpires() throws Exception {
        String email = "cancela-" + UUID.randomUUID() + "@correo.com";
        User user = createUser(email, "Paciente123!", RoleName.PATIENT);
        Patient patient = createPatientForUser(user);
        Appointment past = appointment(patient, LocalDate.now().minusMonths(3), AppointmentStatus.COMPLETED, "Revisión de presión");
        Appointment future = appointment(patient, LocalDate.now().plusDays(5), AppointmentStatus.SCHEDULED, "Seguimiento");

        // "Eliminar mi cuenta" desde el centro de privacidad (con su contraseña).
        HttpHeaders me = authHeaders(loginAndGetToken(email, "Paciente123!"));
        assertThat(restTemplate.exchange(baseUrl("/api/privacy/me/delete-account"), HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "incorrecta"), me), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode created = json(restTemplate.exchange(baseUrl("/api/privacy/me/delete-account"), HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "Paciente123!", "reason", "Ya no uso el servicio"), me), String.class));
        long id = created.at("/data/id").asLong();
        assertThat(created.at("/data/identityMethod").asText()).isEqualTo("SESSION_PASSWORD");

        JsonNode plan = json(admin(HttpMethod.GET, "/api/admin/arco-requests/" + id, null)).at("/data/cancellationPlan");
        assertThat(plan.get("retainUntil").asText()).isEqualTo(past.getAppointmentDate().plusYears(5).toString());
        assertThat(admin(HttpMethod.POST, "/api/admin/arco-requests/" + id + "/respond", Map.of("outcome", "PROCEDENTE")).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode done = json(admin(HttpMethod.POST, "/api/admin/arco-requests/" + id + "/execute", Map.of()));
        assertThat(done.at("/data/executionSummary").asText()).contains("cuenta", "resguardo");

        // La cuenta ya no entra; la cita por venir se canceló; el expediente quedó sin nombre.
        assertThat(restTemplate.postForEntity(baseUrl("/api/auth/login"), Map.of("email", email, "password", "Paciente123!"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(appointmentRepository.findById(future.getId()).orElseThrow().getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        Patient blocked = patientRepository.findById(patient.getId()).orElseThrow();
        assertThat(blocked.getPrivacyStatus()).isEqualTo("BLOCKED");
        assertThat(blocked.getFirstName()).isEqualTo("Paciente");
        assertThat(blocked.getEmail()).isNull();
        assertThat(appointmentRepository.findById(past.getId()).orElseThrow().getReasonForVisit()).isEqualTo("Revisión de presión");

        // El resguardo solo se abre con motivo y conserva la identidad original.
        assertThat(admin(HttpMethod.POST, "/api/admin/patients/" + patient.getId() + "/vault", Map.of("reason", "x")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode vault = json(admin(HttpMethod.POST, "/api/admin/patients/" + patient.getId() + "/vault",
                Map.of("reason", "Requerimiento de la autoridad sanitaria")));
        assertThat(vault.at("/data/email").asText()).isEqualTo(email);

        // Al vencer el plazo legal, el resguardo se borra y el expediente queda anónimo.
        PrivacyVault v = vaultRepository.findFirstByPatient_IdAndPurgedAtIsNullOrderByIdDesc(patient.getId()).orElseThrow();
        v.setRetainUntil(LocalDate.now().minusDays(1));
        vaultRepository.save(v);
        assertThat(privacyService.purgeExpiredVaults()).isPositive();
        assertThat(patientRepository.findById(patient.getId()).orElseThrow().getPrivacyStatus()).isEqualTo("ANONYMIZED");
        assertThat(appointmentRepository.findById(past.getId()).orElseThrow().getReasonForVisit()).contains("eliminado");
        assertThat(vaultRepository.findById(v.getId()).orElseThrow().getPayload()).isNull();
    }

    @Test
    void someoneWithoutClinicalHistoryIsErasedRightAway() throws Exception {
        Patient p = patient("Sin", "Historial", null);
        JsonNode created = json(admin(HttpMethod.POST, "/api/admin/arco-requests/in-person",
                Map.of("patientId", p.getId(), "type", "CANCELLATION", "details", "Borrar mis datos", "identityNotes", "INE en mostrador")));
        long id = created.at("/data/id").asLong();
        admin(HttpMethod.POST, "/api/admin/arco-requests/" + id + "/respond", Map.of("outcome", "PROCEDENTE"));
        admin(HttpMethod.POST, "/api/admin/arco-requests/" + id + "/execute", Map.of());
        Patient after = patientRepository.findById(p.getId()).orElseThrow();
        assertThat(after.getPrivacyStatus()).isEqualTo("ANONYMIZED");
        assertThat(vaultRepository.findFirstByPatient_IdAndPurgedAtIsNullOrderByIdDesc(p.getId())).isEmpty();
    }

    // ── Plazos ──────────────────────────────────────────────────

    @Test
    void anUnansweredInfoRequestClosesTheRequestAsNotFiled() throws Exception {
        Patient p = patient("Info", "Pendiente", null);
        long id = json(admin(HttpMethod.POST, "/api/admin/arco-requests/in-person",
                Map.of("patientId", p.getId(), "type", "RECTIFICATION", "details", "Mi apellido está mal escrito"))).at("/data/id").asLong();
        JsonNode asked = json(admin(HttpMethod.POST, "/api/admin/arco-requests/" + id + "/request-info",
                Map.of("message", "Adjunta tu acta de nacimiento para corregir el apellido")));
        assertThat(asked.at("/data/status").asText()).isEqualTo("INFO_REQUESTED");
        ArcoRequest a = arcoRequestRepository.findById(id).orElseThrow();
        a.setInfoDue(LocalDate.now().minusDays(1));
        arcoRequestRepository.save(a);
        arcoRequestService.maintenance();
        assertThat(arcoRequestRepository.findById(id).orElseThrow().getStatus().name()).isEqualTo("NOT_FILED");
    }

    @Test
    void businessDaysSkipWeekendsAndOfficialHolidays() {
        nonBusinessDayRepository.save(new NonBusinessDay(LocalDate.of(2026, 11, 16), "Día de la Revolución"));
        BusinessCalendar calendar = new BusinessCalendar(nonBusinessDayRepository);
        // Viernes 13 + 1 día hábil: el sábado, domingo y el lunes 16 (inhábil) no cuentan.
        assertThat(calendar.addBusinessDays(LocalDate.of(2026, 11, 13), 1)).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(calendar.addBusinessDays(LocalDate.of(2026, 11, 13), 20)).isEqualTo(LocalDate.of(2026, 12, 14));
    }

    // ── Centro de privacidad del paciente ───────────────────────

    @Test
    void thePatientDownloadsTheirDataAndOptsOutOfRemindersFromThePrivacyCenter() throws Exception {
        String email = "centro-" + UUID.randomUUID() + "@correo.com";
        User user = createUser(email, "Paciente123!", RoleName.PATIENT);
        Patient patient = createPatientForUser(user);
        HttpHeaders me = authHeaders(loginAndGetToken(email, "Paciente123!"));

        assertThat(restTemplate.exchange(baseUrl("/api/privacy/me/export"), HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "mala"), me), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<byte[]> pdf = restTemplate.exchange(baseUrl("/api/privacy/me/export"), HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "Paciente123!"), me), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdfText(pdf.getBody())).contains("Reporte de datos personales");

        assertThat(restTemplate.exchange(baseUrl("/api/privacy/me/preferences"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("reminders", false), me), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patientRepository.findById(patient.getId()).orElseThrow().getNotifyReminders()).isFalse();
        JsonNode center = json(restTemplate.exchange(baseUrl("/api/privacy/me"), HttpMethod.GET, new HttpEntity<>(me), String.class)).get("data");
        assertThat(center.get("consents").toString()).contains("REMINDERS");
    }

    // ── Reglas y aislamiento ────────────────────────────────────

    @Test
    void crossFieldRulesAreEnforced() {
        String email = "reglas-" + UUID.randomUUID() + "@correo.com";
        patient("Lucía", "Pérez", email);
        Map<String, Object> noPrivacy = body(email, "ACCESS", null);
        noPrivacy.put("acceptPrivacyNotice", false);
        assertThat(submit(noPrivacy).getBody()).contains("aviso de privacidad");
        assertThat(submit(body(email, "RECTIFICATION", null)).getBody()).contains("qué dato");
        Map<String, Object> rep = body(email, "ACCESS", null);
        rep.put("representative", true);
        assertThat(submit(rep).getBody()).contains("representante");
        Map<String, Object> badName = body(email, "ACCESS", null);
        badName.put("requesterName", "<script>alert(1)</script>");
        assertThat(submit(badName).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anotherHospitalsAdminNeverSeesTheRequest() throws Exception {
        Patient p = patient("Aislada", "Prueba", null);
        long id = json(admin(HttpMethod.POST, "/api/admin/arco-requests/in-person",
                Map.of("patientId", p.getId(), "type", "ACCESS"))).at("/data/id").asLong();

        String slug = "otro-arco-" + UUID.randomUUID().toString().substring(0, 6);
        Hospital other = TenantContext.callAs(TenantContext.ROOT,
                () -> hospitalRepository.save(Hospital.builder().name("Otro").slug(slug).build()));
        String adminEmail = "admin-" + UUID.randomUUID() + "@otro.com";
        TenantContext.runAs(other.getId(), () -> createUser(adminEmail, "Admin123!", RoleName.ADMIN));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hospital", slug);
        String token = json(restTemplate.exchange(baseUrl("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(Map.of("email", adminEmail, "password", "Admin123!"), headers), String.class)).at("/data/token").asText();
        HttpHeaders otherAdmin = authHeaders(token);
        otherAdmin.set("X-Hospital", slug);
        assertThat(json(restTemplate.exchange(baseUrl("/api/admin/arco-requests"), HttpMethod.GET,
                new HttpEntity<>(otherAdmin), String.class)).get("data").findValuesAsText("id")).doesNotContain(String.valueOf(id));
        assertThat(restTemplate.exchange(baseUrl("/api/admin/arco-requests/" + id), HttpMethod.GET,
                new HttpEntity<>(otherAdmin), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
