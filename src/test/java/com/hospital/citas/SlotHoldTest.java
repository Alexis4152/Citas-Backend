package com.hospital.citas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.entity.Branch;
import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.entity.SlotHold;
import com.hospital.citas.repository.SlotHoldRepository;
import com.hospital.citas.service.SlotHoldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Apartado temporal de horarios: dos personas que eligen el mismo horario al mismo tiempo, el
 * aviso "alguien lo está apartando", y que el horario vuelva a quedar libre si no se confirma.
 */
class SlotHoldTest extends AbstractIntegrationTest {

    @Autowired
    private SlotHoldRepository slotHoldRepository;
    @Autowired
    private SlotHoldService slotHoldService;
    @Autowired
    private ObjectMapper objectMapper;

    private static final LocalTime TEN = LocalTime.of(10, 0);

    private record Fixture(Doctor doctor, Branch branch, LocalDate date) {}

    private Fixture fixture() {
        Branch branch = createBranch("Sede-Hold");
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, createSpecialty("Esp-Hold"));
        return new Fixture(doctor, branch, LocalDate.now().plusDays(6));
    }

    private static String holder() {
        return "tab-" + UUID.randomUUID();
    }

    private ResponseEntity<String> hold(Fixture f, LocalTime time, String holderKey) {
        Map<String, Object> body = Map.of("doctorId", f.doctor().getId(), "branchId", f.branch().getId(),
                "date", f.date().toString(), "startTime", time.toString(), "holderKey", holderKey);
        return restTemplate.postForEntity(baseUrl("/api/public/slot-holds"), body, String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private Map<String, Object> guestBody(Fixture f, LocalTime time, String holdToken, String holderKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Invitada");
        body.put("lastName", "Apartado " + randomLetters(6));
        body.put("phone", "55" + (10000000 + new Random().nextInt(89999999)));
        body.put("doctorId", f.doctor().getId());
        body.put("branchId", f.branch().getId());
        body.put("appointmentDate", f.date().toString());
        body.put("startTime", time.toString());
        body.put("medicalInfo", Map.of("allergies", "Ninguna", "bloodType", "O_POSITIVE"));
        body.put("acceptPrivacyNotice", true);
        if (holdToken != null) body.put("holdToken", holdToken);
        if (holderKey != null) body.put("holderKey", holderKey);
        return body;
    }

    private JsonNode slotAt(Fixture f, LocalTime time, String holderKey) throws Exception {
        String url = "/api/public/doctors/" + f.doctor().getId() + "/availability?date=" + f.date() + "&days=1"
                + (holderKey != null ? "&holder=" + holderKey : "");
        JsonNode days = json(restTemplate.getForEntity(baseUrl(url), String.class)).get("data");
        for (JsonNode slot : days.get(0).get("slots")) {
            if (slot.get("startTime").asText().startsWith(time.toString())) {
                return slot;
            }
        }
        throw new AssertionError("No existe el horario " + time);
    }

    @Test
    void whileSomeoneHoldsASlotOthersSeeItAsBeingHeldAndCannotTakeIt() throws Exception {
        Fixture f = fixture();
        String ana = holder();
        String beto = holder();

        ResponseEntity<String> anaHold = hold(f, TEN, ana);
        assertThat(anaHold.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(anaHold).at("/data/secondsLeft").asLong()).isBetween(1L, 600L);

        // Ana lo sigue viendo como suyo; Beto, como "alguien lo está apartando".
        JsonNode forAna = slotAt(f, TEN, ana);
        assertThat(forAna.get("available").asBoolean()).isTrue();
        assertThat(forAna.get("heldByMe").asBoolean()).isTrue();
        JsonNode forBeto = slotAt(f, TEN, beto);
        assertThat(forBeto.get("available").asBoolean()).isFalse();
        assertThat(forBeto.get("held").asBoolean()).isTrue();

        // Beto no puede apartarlo ni agendarlo directo (409 con código SLOT_HELD).
        ResponseEntity<String> betoHold = hold(f, TEN, beto);
        assertThat(betoHold.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(betoHold).at("/data/code").asText()).isEqualTo("SLOT_HELD");
        assertThat(json(betoHold).get("message").asText()).contains("apartando");
        ResponseEntity<String> betoBooks = restTemplate.postForEntity(baseUrl("/api/appointments/guest"),
                guestBody(f, TEN, null, beto), String.class);
        assertThat(betoBooks.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Ana confirma con su apartado: queda la cita y el apartado se consume.
        String token = json(anaHold).at("/data/token").asText();
        ResponseEntity<String> anaBooks = restTemplate.postForEntity(baseUrl("/api/appointments/guest"),
                guestBody(f, TEN, token, ana), String.class);
        assertThat(anaBooks.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(slotHoldRepository.findByToken(UUID.fromString(token))).isEmpty();
        assertThat(slotAt(f, TEN, beto).get("held").asBoolean()).isFalse();
        assertThat(slotAt(f, TEN, beto).get("available").asBoolean()).isFalse();
    }

    @Test
    void manyPeopleHoldingTheSameSlotAtTheSameInstantOnlyOneWins() throws Exception {
        Fixture f = fixture();
        int people = 12;
        ExecutorService pool = Executors.newFixedThreadPool(people);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatusCode>> results = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            String key = holder();
            results.add(pool.submit(() -> {
                start.await();
                return hold(f, TEN, key).getStatusCode();
            }));
        }
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<HttpStatusCode> r : results) {
            HttpStatusCode status = r.get(30, TimeUnit.SECONDS);
            if (status.value() == 200) ok++;
            if (status.value() == 409) conflict++;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(people - 1);
    }

    @Test
    void manyPeopleBookingTheSameSlotAtTheSameInstantOnlyOneAppointmentIsCreated() throws Exception {
        Fixture f = fixture();
        int people = 10;
        ExecutorService pool = Executors.newFixedThreadPool(people);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatusCode>> results = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            Map<String, Object> body = guestBody(f, TEN, null, holder());
            results.add(pool.submit(() -> {
                start.await();
                return restTemplate.postForEntity(baseUrl("/api/appointments/guest"), body, String.class).getStatusCode();
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<HttpStatusCode> r : results) {
            if (r.get(30, TimeUnit.SECONDS).value() == 201) created++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
    }

    @Test
    void releasingOrLettingTheHoldExpireFreesTheSlotForOthers() throws Exception {
        Fixture f = fixture();
        String ana = holder();
        String beto = holder();

        String token = json(hold(f, TEN, ana)).at("/data/token").asText();
        restTemplate.exchange(baseUrl("/api/public/slot-holds/" + token), HttpMethod.DELETE, null, String.class);
        assertThat(slotAt(f, TEN, beto).get("available").asBoolean()).isTrue();

        // Vence sin confirmar: deja de estorbar aunque la fila siga ahí.
        hold(f, TEN, ana);
        SlotHold held = slotHoldRepository.findByHolderKey(ana).get(0);
        held.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        slotHoldRepository.save(held);
        assertThat(slotAt(f, TEN, beto).get("available").asBoolean()).isTrue();
        assertThat(hold(f, TEN, beto).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aTabHoldsOnlyOneSlotAndChoosingAnotherReleasesThePrevious() throws Exception {
        Fixture f = fixture();
        String ana = holder();
        hold(f, TEN, ana);
        assertThat(hold(f, LocalTime.of(11, 0), ana).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(slotHoldRepository.findByHolderKey(ana)).hasSize(1);
        assertThat(slotAt(f, TEN, holder()).get("available").asBoolean()).isTrue();
        assertThat(slotAt(f, LocalTime.of(11, 0), holder()).get("held").asBoolean()).isTrue();
    }

    @Test
    void oneConnectionCannotHoardManySlots() {
        Fixture f = fixture();
        Object previous = ReflectionTestUtils.getField(slotHoldService, "maxHoldsPerIp");
        ReflectionTestUtils.setField(slotHoldService, "maxHoldsPerIp", 3);
        try {
            slotHoldRepository.deleteAll();
            for (int i = 0; i < 3; i++) {
                assertThat(hold(f, LocalTime.of(9 + i, 0), holder()).getStatusCode()).isEqualTo(HttpStatus.OK);
            }
            ResponseEntity<String> fourth = hold(f, LocalTime.of(14, 0), holder());
            assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(fourth.getBody()).contains("demasiados horarios apartados");
        } finally {
            ReflectionTestUtils.setField(slotHoldService, "maxHoldsPerIp", previous);
        }
    }

    @Test
    void receptionSeesTheHeldSlotAndCanOnlyTakeItAsOverbooking() throws Exception {
        Fixture f = fixture();
        hold(f, TEN, holder());
        Patient walkIn = patientRepository.save(Patient.builder().firstName("Mostrador").lastName("Prueba")
                .phone("55" + (10000000 + new Random().nextInt(89999999))).build());
        String reception = receptionistToken();
        Map<String, Object> plain = Map.of("patientId", walkIn.getId(), "doctorId", f.doctor().getId(),
                "branchId", f.branch().getId(), "appointmentDate", f.date().toString(), "startTime", "10:00");
        Map<String, Object> overbook = new HashMap<>(plain);
        overbook.put("overbook", true);

        ResponseEntity<String> blocked = restTemplate.exchange(baseUrl("/api/reception/appointments"), HttpMethod.POST,
                new HttpEntity<>(plain, authHeaders(reception)), String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> extra = restTemplate.exchange(baseUrl("/api/reception/appointments"), HttpMethod.POST,
                new HttpEntity<>(overbook, authHeaders(reception)), String.class);
        assertThat(extra.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(extra).at("/data/overbooked").asBoolean()).isTrue();
    }

    @Test
    void cancellingAnAppointmentMakesTheSlotAvailableAgain() throws Exception {
        Fixture f = fixture();
        ResponseEntity<String> booked = restTemplate.postForEntity(baseUrl("/api/appointments/guest"),
                guestBody(f, TEN, null, null), String.class);
        assertThat(booked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(slotAt(f, TEN, null).get("available").asBoolean()).isFalse();

        String token = json(booked).at("/data/cancelToken").asText();
        restTemplate.postForEntity(baseUrl("/api/appointments/cancel/" + token), null, String.class);
        assertThat(slotAt(f, TEN, null).get("available").asBoolean()).isTrue();
    }

    @Test
    void availabilityNeverReturnsMoreThan31Days() throws Exception {
        Fixture f = fixture();
        String url = "/api/public/doctors/" + f.doctor().getId() + "/availability?date=" + f.date() + "&days=5000";
        assertThat(json(restTemplate.getForEntity(baseUrl(url), String.class)).get("data").size()).isEqualTo(31);
    }
}
