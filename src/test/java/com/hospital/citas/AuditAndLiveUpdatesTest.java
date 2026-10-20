package com.hospital.citas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.entity.Branch;
import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Bitácora de acceso a expedientes y avisos en vivo de cambios en la agenda. */
class AuditAndLiveUpdatesTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void viewingAPatientFileIsRecordedAndOnlyTheAdminCanReadTheLog() throws Exception {
        Patient patient = patientRepository.save(Patient.builder().firstName("Bitácora").lastName("Prueba").phone("5511122233").build());
        String reception = receptionistToken();
        String viewer = adminToken();
        ResponseEntity<String> view = restTemplate.exchange(baseUrl("/api/reception/patients/" + patient.getId()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(viewer)), String.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Una consulta que falla (no existe) no se registra.
        restTemplate.exchange(baseUrl("/api/reception/patients/999999"), HttpMethod.GET, new HttpEntity<>(authHeaders(viewer)), String.class);

        String admin = adminToken();
        JsonNode log = objectMapper.readTree(restTemplate.exchange(
                baseUrl("/api/admin/audit-logs?entityType=PATIENT&entityId=" + patient.getId()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)), String.class).getBody()).at("/data/content");
        assertThat(log).hasSize(1);
        assertThat(log.get(0).get("action").asText()).isEqualTo("VIEW_PATIENT");
        assertThat(log.get(0).get("userRole").asText()).isEqualTo("ADMIN");
        assertThat(log.get(0).get("actionLabel").asText()).isEqualTo("Consultó el expediente");

        assertThat(restTemplate.exchange(baseUrl("/api/admin/audit-logs"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(reception)), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void whoeverIsWatchingADoctorGetsALiveNoticeWhenSomeoneHoldsASlot() throws Exception {
        Branch branch = createBranch("Sede-Vivo");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Vivo"));

        HttpURLConnection stream = (HttpURLConnection) new URL(baseUrl(
                "/api/public/doctors/" + doctor.getId() + "/availability/stream?hospital=" + TEST_HOSPITAL_SLUG)).openConnection();
        stream.setReadTimeout(10_000);
        stream.setRequestProperty("Accept", "text/event-stream");
        assertThat(stream.getResponseCode()).isEqualTo(200);
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Boolean> gotChange = pool.submit(() -> {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("event:changed")) return true;
            }
            return false;
        });
        Thread.sleep(300);
        Map<String, Object> hold = Map.of("doctorId", doctor.getId(), "branchId", branch.getId(),
                "date", LocalDate.now().plusDays(4).toString(), "startTime", "10:00", "holderKey", "tab-" + UUID.randomUUID());
        assertThat(restTemplate.postForEntity(baseUrl("/api/public/slot-holds"), hold, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(gotChange.get(8, TimeUnit.SECONDS)).isTrue();
        stream.disconnect();
        pool.shutdownNow();
    }

    @Test
    void anUnknownHospitalCannotOpenALiveStream() {
        ResponseEntity<String> r = restTemplate.getForEntity(baseUrl("/api/public/doctors/1/availability/stream?hospital=no-existe"), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
