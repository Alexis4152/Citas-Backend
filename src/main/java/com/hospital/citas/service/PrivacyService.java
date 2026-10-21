package com.hospital.citas.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.PaymentStatus;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.pdf.FlowDocument;
import com.hospital.citas.pdf.LogoLoader;
import com.hospital.citas.repository.*;
import com.hospital.citas.security.CredentialEncryptionService;
import com.hospital.citas.service.impl.AppointmentServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Lo que la plataforma hace con los datos de una persona cuando ejerce sus derechos (el
 * "back-office" de privacidad, al estilo de Mercado Libre):
 * <ul>
 *   <li><b>Acceso:</b> reporte PDF con todo lo que el hospital guarda de ella y para qué.</li>
 *   <li><b>Cancelación:</b> cada categoría de datos se trata distinto: la cuenta se suprime ya;
 *   lo que la ley obliga a conservar (expediente clínico, NOM-004; pagos, obligaciones fiscales)
 *   se <i>bloquea</i>: la identidad se separa a un resguardo cifrado y el expediente queda sin
 *   nombre; al vencer el plazo, el resguardo se borra y queda anónimo para siempre.</li>
 *   <li><b>Oposición / limitación:</b> preferencias que el sistema respeta (recordatorios, novedades).</li>
 *   <li><b>Rectificación de algo clínico:</b> nota aclaratoria (lo clínico no se altera).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DMY_HM = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    public static final String BLOCKED = "BLOCKED";
    public static final String ANONYMIZED = "ANONYMIZED";

    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final AppointmentRepository appointmentRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final PaymentRepository paymentRepository;
    private final PrivacyVaultRepository vaultRepository;
    private final PatientClarificationRepository clarificationRepository;
    private final HospitalConfigService hospitalConfigService;
    private final ConsentService consentService;
    private final CredentialEncryptionService encryption;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final LogoLoader logoLoader;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final AppointmentServiceImpl appointmentService;

    // ── Acceso: reporte de datos personales ─────────────────────

    @Transactional(readOnly = true)
    public byte[] dataReport(Patient patient, User account, String reference) {
        // Se vuelven a leer dentro de la transacción (pueden venir de fuera, sin sesión abierta).
        if (patient != null) patient = patientRepository.findById(patient.getId()).orElse(patient);
        if (account != null) account = userRepository.findById(account.getId()).orElse(account);
        HospitalConfig hospital = hospitalConfigService.getEntity();
        String who = patient != null ? patient.getFirstName() + " " + patient.getLastName()
                : account != null ? account.getFirstName() + " " + account.getLastName() : "Titular";
        try (FlowDocument d = new FlowDocument(hospital, logoLoader, "Derecho de acceso", "Reporte de datos personales",
                who + (reference != null ? " · " + reference : "")
                        + ". Contiene todos los datos personales que " + hospital.getName()
                        + " guarda de ti, para qué los usa, con quién los comparte y cuánto tiempo los conserva.")) {
            if (patient != null) {
                d.section("Identificación y contacto").fields(List.of(
                        new String[]{"Nombre", patient.getFirstName() + " " + patient.getLastName()},
                        new String[]{"Teléfono", patient.getPhone()},
                        new String[]{"Correo", patient.getEmail()},
                        new String[]{"Fecha de nacimiento", patient.getBirthDate() == null ? null
                                : patient.getBirthDate().format(DMY) + " (" + Period.between(patient.getBirthDate(), LocalDate.now()).getYears() + " años)"},
                        new String[]{"Sexo", sexLabel(patient.getSex())},
                        new String[]{"CURP", patient.getCurp()},
                        new String[]{"Domicilio", address(patient)},
                        new String[]{"Contacto de emergencia", patient.getEmergencyContactName() == null ? null
                                : patient.getEmergencyContactName() + " · " + patient.getEmergencyContactPhone()},
                        new String[]{"Registrado desde", patient.getCreatedAt() == null ? null : patient.getCreatedAt().format(DMY)},
                        new String[]{"Cuenta de acceso", patient.getUser() != null ? "Sí · " + patient.getUser().getEmail() : "No"}));
                d.section("Información médica (dato sensible)").fields(List.of(
                        new String[]{"Alergias", patient.getAllergies()},
                        new String[]{"Tipo de sangre", patient.getBloodType() == null ? null
                                : bloodLabel(patient.getBloodType().name()) + (patient.getBloodTypeOther() != null ? " (" + patient.getBloodTypeOther() + ")" : "")},
                        new String[]{"Última actualización", patient.getMedicalInfoUpdatedAt() == null ? null : patient.getMedicalInfoUpdatedAt().format(DMY_HM)}));

                List<Appointment> appts = appointmentRepository.findByPatient_IdOrderByAppointmentDateDescStartTimeDesc(patient.getId());
                List<String[]> apptRows = new ArrayList<>();
                for (Appointment a : appts) {
                    apptRows.add(new String[]{a.getAppointmentDate().format(DMY) + " " + a.getStartTime().toString().substring(0, 5),
                            "Dr(a). " + a.getDoctor().getUser().getFirstName() + " " + a.getDoctor().getUser().getLastName(),
                            a.getDoctor().getSpecialty().getName(), a.getBranch().getName(), statusLabel(a.getStatus()), a.getReasonForVisit()});
                }
                d.section("Citas (" + appts.size() + ")").table(new String[]{"Fecha", "Doctor", "Especialidad", "Sede", "Estado", "Motivo"},
                        new float[]{1.1f, 1.6f, 1.2f, 1f, 0.9f, 2f}, apptRows, "Sin citas registradas.");

                List<Prescription> rxs = prescriptionRepository.findByAppointment_Patient_IdWithDetails(patient.getId());
                d.section("Recetas (" + rxs.size() + ")");
                if (rxs.isEmpty()) d.note("Sin recetas registradas.");
                for (Prescription rx : rxs) {
                    d.paragraph("Cita del " + rx.getAppointment().getAppointmentDate().format(DMY) + " · Dr(a). "
                            + rx.getAppointment().getDoctor().getUser().getFirstName() + " " + rx.getAppointment().getDoctor().getUser().getLastName()
                            + (rx.getVoidedAt() != null ? " · ANULADA" : ""));
                    if (rx.getDiagnosis() != null) d.note("Diagnóstico: " + rx.getDiagnosis());
                    d.note("Indicaciones: " + rx.getContent());
                }

                List<Payment> payments = paymentRepository.findByPatientId(patient.getId());
                List<String[]> payRows = new ArrayList<>();
                for (Payment p : payments) {
                    payRows.add(new String[]{p.getCreatedAt().format(DMY_HM), "#" + String.format("%06d", p.getAppointment().getId()),
                            com.hospital.citas.report.ReportCatalog.methodLabel(p.getMethod()), "$" + p.getAmount().toPlainString(),
                            p.getStatus() == PaymentStatus.COMPLETED ? "Pagado" : p.getStatus().name()});
                }
                d.section("Pagos (" + payments.size() + ")").table(new String[]{"Fecha", "Cita", "Método", "Monto", "Estado"},
                        new float[]{1.2f, 0.8f, 1.2f, 0.8f, 0.8f}, payRows, "Sin pagos registrados. Los datos de tarjeta los procesa la pasarela de pagos; el hospital no los guarda.");

                List<PatientClarification> notes = clarificationRepository.listForPatient(patient.getId());
                if (!notes.isEmpty()) {
                    d.section("Notas aclaratorias del expediente");
                    d.bullets(notes.stream().map(n -> n.getCreatedAt().format(DMY) + ": " + n.getNote()).toList());
                }
                d.section("Preferencias de uso").fields(List.of(
                        new String[]{"Recordatorios de citas por correo", Boolean.FALSE.equals(patient.getNotifyReminders()) ? "No" : "Sí"},
                        new String[]{"Novedades y encuestas", Boolean.TRUE.equals(patient.getNotifyNews()) ? "Sí" : "No"}));
            } else if (account != null) {
                d.section("Cuenta").fields(List.of(
                        new String[]{"Nombre", account.getFirstName() + " " + account.getLastName()},
                        new String[]{"Correo", account.getEmail()},
                        new String[]{"Teléfono", account.getPhone()},
                        new String[]{"Tipo de cuenta", account.getRole().getName().name()}));
            }
            List<Map<String, Object>> consents = consentService.history(patient, account);
            d.section("Consentimientos otorgados");
            if (consents.isEmpty()) d.note("Sin constancias de consentimiento registradas.");
            else d.bullets(consents.stream().map(c -> ((LocalDateTime) c.get("at")).format(DMY_HM) + " · " + scopeLabel((String) c.get("scope"))
                    + ": " + (Boolean.TRUE.equals(c.get("granted")) ? "otorgado" : "retirado") + " (aviso v" + c.get("noticeVersion") + ", " + c.get("channel") + ")").toList());

            d.section("Para qué usamos tus datos").bullets(List.of(
                    "Agendar, confirmar, reprogramar y recordarte tus citas.",
                    "Que tu doctor te atienda con seguridad (alergias, tipo de sangre, motivo de consulta, recetas).",
                    "Cobrar la consulta y emitir comprobantes.",
                    "Cumplir obligaciones legales: expediente clínico (NOM-004-SSA3-2012) y obligaciones fiscales.",
                    "Finalidad secundaria, solo si la aceptas: novedades y encuestas del hospital."));
            d.section("Con quién se comparten").bullets(List.of(
                    "Personal del hospital que te atiende (doctor, recepción y administración).",
                    "Proveedores que operan el servicio por cuenta del hospital: plataforma de citas (Nexora Systems), envío de correo y pasarela de pagos, con obligación de confidencialidad.",
                    "Autoridades, solo cuando la ley lo exige."));
            d.section("Cuánto tiempo se guardan").paragraph("El expediente clínico se conserva al menos "
                    + hospital.getClinicalRetentionYears() + " años desde la última atención, y los datos de pagos "
                    + hospital.getFiscalRetentionYears() + " años, aunque pidas su cancelación: durante ese tiempo quedan bloqueados "
                    + "(nadie los usa) y después se eliminan. El resto se elimina cuando ya no es necesario.");
            d.section("Tus derechos").paragraph("Puedes pedir el acceso, la rectificación, la cancelación o la oposición al uso de tus datos, "
                    + "revocar tu consentimiento o limitar su uso desde la página de derechos ARCO del hospital. Respondemos en 20 días hábiles "
                    + "y, si procede, lo hacemos efectivo en los 15 días hábiles siguientes. Si no estás de acuerdo con la respuesta, puedes acudir "
                    + "a la Secretaría Anticorrupción y Buen Gobierno.");
            return d.finish();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo generar el reporte de datos personales", e);
        }
    }

    // ── Cancelación: plan y ejecución ───────────────────────────

    public record PlanItem(String category, String action, String detail) {
    }

    public record CancellationPlan(LocalDate retainUntil, List<PlanItem> items) {
    }

    /** Qué pasará con cada categoría de datos (se le muestra al admin antes de ejecutar). */
    @Transactional(readOnly = true)
    public CancellationPlan cancellationPlan(Patient patient, User account) {
        HospitalConfig hospital = hospitalConfigService.getEntity();
        List<PlanItem> items = new ArrayList<>();
        LocalDate retainUntil = null;
        if (account != null || (patient != null && patient.getUser() != null)) {
            items.add(new PlanItem("Cuenta de acceso", "Se elimina ahora",
                    "Se desactiva, se cierran sus sesiones y se borran su nombre, correo y contraseña."));
        }
        if (patient != null) {
            List<Appointment> appts = appointmentRepository.findByPatient_IdOrderByAppointmentDateDescStartTimeDesc(patient.getId());
            long upcoming = appts.stream().filter(a -> a.getStatus() == AppointmentStatus.SCHEDULED
                    && !a.getAppointmentDate().isBefore(LocalDate.now())).count();
            if (upcoming > 0) items.add(new PlanItem("Citas por venir", "Se cancelan ahora",
                    upcoming + " cita(s) programada(s) se cancelan y su horario se libera."));
            Optional<LocalDate> lastMedical = appts.stream().filter(a -> a.getStatus() == AppointmentStatus.COMPLETED)
                    .map(Appointment::getAppointmentDate).max(Comparator.naturalOrder());
            Optional<LocalDate> lastPayment = paymentRepository.findByPatientId(patient.getId()).stream()
                    .filter(p -> p.getStatus() == PaymentStatus.COMPLETED || p.getStatus() == PaymentStatus.REFUNDED)
                    .map(p -> p.getCreatedAt().toLocalDate()).max(Comparator.naturalOrder());
            LocalDate clinicalUntil = lastMedical.map(d -> d.plusYears(hospital.getClinicalRetentionYears())).orElse(null);
            LocalDate fiscalUntil = lastPayment.map(d -> d.plusYears(hospital.getFiscalRetentionYears())).orElse(null);
            if (clinicalUntil != null) items.add(new PlanItem("Expediente clínico", "Se bloquea hasta el " + clinicalUntil.format(DMY),
                    "Atenciones, recetas y datos médicos se conservan sin nombre (NOM-004: " + hospital.getClinicalRetentionYears()
                            + " años desde la última atención, el " + lastMedical.get().format(DMY) + "). Después se eliminan."));
            if (fiscalUntil != null) items.add(new PlanItem("Pagos", "Se bloquean hasta el " + fiscalUntil.format(DMY),
                    "Montos y fechas se conservan por obligaciones fiscales (" + hospital.getFiscalRetentionYears() + " años), sin nombre."));
            retainUntil = clinicalUntil == null ? fiscalUntil : fiscalUntil == null ? clinicalUntil
                    : clinicalUntil.isAfter(fiscalUntil) ? clinicalUntil : fiscalUntil;
            items.add(new PlanItem("Identidad y contacto", retainUntil == null ? "Se elimina ahora" : "Se separa y se resguarda cifrada",
                    retainUntil == null
                            ? "No hay nada que la ley obligue a conservar: nombre, teléfono, correo, domicilio y datos médicos se borran."
                            : "Nombre, teléfono, correo, domicilio y datos médicos salen del expediente y quedan en un resguardo cifrado "
                              + "que solo el administrador abre con motivo registrado; se borra el " + retainUntil.format(DMY) + "."));
            items.add(new PlanItem("Recordatorios y novedades", "Se apagan ahora", "No se le vuelve a contactar."));
        }
        items.add(new PlanItem("Esta solicitud", "Se conserva", "La solicitud y su constancia se guardan como prueba de que se atendió el derecho."));
        return new CancellationPlan(retainUntil, items);
    }

    /** Ejecuta la Cancelación. Devuelve lo que se hizo, para la constancia. */
    @Transactional
    public List<String> executeCancellation(Patient patient, User account, ArcoRequest arco, User actor) {
        CancellationPlan plan = cancellationPlan(patient, account);
        String folio = "ARCO-" + String.format("%06d", arco.getId());
        List<String> done = new ArrayList<>();
        User linked = account != null ? account : patient != null ? patient.getUser() : null;

        if (patient != null) {
            List<Appointment> upcoming = appointmentRepository.findByPatient_IdOrderByAppointmentDateDescStartTimeDesc(patient.getId()).stream()
                    .filter(a -> a.getStatus() == AppointmentStatus.SCHEDULED && !a.getAppointmentDate().isBefore(LocalDate.now())).toList();
            for (Appointment a : upcoming) {
                appointmentService.cancelForPrivacyRequest(a, actor, "Cancelada por solicitud de cancelación de datos " + folio);
            }
            if (!upcoming.isEmpty()) done.add("Se cancelaron " + upcoming.size() + " cita(s) por venir y se liberaron sus horarios.");
        }
        if (linked != null && linked.getRole().getName() == RoleName.PATIENT) {
            eraseAccount(linked);
            done.add("Se eliminó la cuenta de acceso y se cerraron sus sesiones.");
        }
        if (patient != null) {
            Patient fresh = patientRepository.findById(patient.getId()).orElseThrow();
            consentService.record(fresh, null, ConsentRecord.NOTICE, false, "ARCO");
            if (plan.retainUntil() != null) {
                vaultRepository.save(PrivacyVault.builder()
                        .patient(fresh).arcoRequest(arco)
                        .payload(encryption.encrypt(toJson(identityOf(fresh))))
                        .retainUntil(plan.retainUntil())
                        .build());
                stripIdentity(fresh, "bloqueado " + folio);
                fresh.setPrivacyStatus(BLOCKED);
                fresh.setBlockedAt(LocalDateTime.now());
                fresh.setRetainUntil(plan.retainUntil());
                done.add("Se separó la identidad del expediente y quedó en resguardo cifrado hasta el " + plan.retainUntil().format(DMY)
                        + " (expediente clínico y pagos bloqueados por obligación legal).");
            } else {
                stripIdentity(fresh, "anónimo " + folio);
                anonymizeClinicalText(fresh);
                fresh.setPrivacyStatus(ANONYMIZED);
                fresh.setAnonymizedAt(LocalDateTime.now());
                done.add("Se eliminaron los datos de identificación, contacto y médicos (no había nada que la ley obligue a conservar).");
            }
            fresh.setIsActive(false);
            fresh.setDeletedAt(LocalDateTime.now());
            fresh.setDeletedBy(actor);
            patientRepository.save(fresh);
            done.add("Se apagaron los recordatorios y novedades.");
        }
        return done;
    }

    private void eraseAccount(User user) {
        refreshTokenService.revokeAllForUser(user.getId());
        user.setIsActive(false);
        user.setFirstName("Cuenta");
        user.setLastName("eliminada");
        user.setPhone(null);
        user.setEmail("eliminada-" + user.getId() + "-" + System.currentTimeMillis() + "@anonimo.invalid");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    private Map<String, Object> identityOf(Patient p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firstName", p.getFirstName());
        m.put("lastName", p.getLastName());
        m.put("phone", p.getPhone());
        m.put("email", p.getEmail());
        m.put("emailMoved", p.getEmailMoved());
        m.put("birthDate", p.getBirthDate() == null ? null : p.getBirthDate().toString());
        m.put("sex", p.getSex());
        m.put("curp", p.getCurp());
        m.put("addressLine", p.getAddressLine());
        m.put("neighborhood", p.getNeighborhood());
        m.put("city", p.getCity());
        m.put("state", p.getState());
        m.put("postalCode", p.getPostalCode());
        m.put("emergencyContactName", p.getEmergencyContactName());
        m.put("emergencyContactPhone", p.getEmergencyContactPhone());
        m.put("allergies", p.getAllergies());
        m.put("bloodType", p.getBloodType() == null ? null : p.getBloodType().name());
        m.put("bloodTypeOther", p.getBloodTypeOther());
        return m;
    }

    private static void stripIdentity(Patient p, String label) {
        p.setFirstName("Paciente");
        p.setLastName(label);
        p.setPhone("");
        p.setEmail(null);
        p.setEmailMoved(null);
        p.setBirthDate(null);
        p.setSex(null);
        p.setCurp(null);
        p.setAddressLine(null);
        p.setNeighborhood(null);
        p.setCity(null);
        p.setState(null);
        p.setPostalCode(null);
        p.setEmergencyContactName(null);
        p.setEmergencyContactPhone(null);
        p.setAllergies(null);
        p.setBloodType(null);
        p.setBloodTypeOther(null);
        p.setNotifyReminders(false);
        p.setNotifyNews(false);
        p.setUser(null);
    }

    /** Al vencer la conservación (o si no había obligación) se borra también el texto clínico libre. */
    private void anonymizeClinicalText(Patient p) {
        String gone = "[eliminado: venció el plazo de conservación]";
        for (Appointment a : appointmentRepository.findByPatient_IdOrderByAppointmentDateDescStartTimeDesc(p.getId())) {
            a.setReasonForVisit(a.getReasonForVisit() == null ? null : gone);
            a.setAllergiesSnapshot(null);
            a.setBloodTypeSnapshot(null);
            a.setBloodTypeOtherSnapshot(null);
            a.setCancelReason(a.getCancelReason() == null ? null : gone);
            appointmentRepository.save(a);
        }
        for (Prescription rx : prescriptionRepository.findByAppointment_Patient_IdWithDetails(p.getId())) {
            rx.setContent(gone);
            rx.setDiagnosis(rx.getDiagnosis() == null ? null : gone);
            prescriptionRepository.save(rx);
        }
    }

    // ── Resguardo ───────────────────────────────────────────────

    /** El administrador abre el resguardo de un paciente bloqueado (con motivo, queda en bitácora). */
    @Transactional(readOnly = true)
    public Map<String, Object> openVault(Long patientId, String reason, User actor) {
        if (reason == null || reason.trim().length() < 10) {
            throw new BusinessException("Escribe el motivo (mínimo 10 caracteres): por ejemplo, un requerimiento de autoridad o una urgencia médica");
        }
        PrivacyVault vault = vaultRepository.findFirstByPatient_IdAndPurgedAtIsNullOrderByIdDesc(patientId)
                .orElseThrow(() -> new BusinessException("Este paciente no tiene datos en resguardo"));
        auditService.record(actor, "PRIVACY_VAULT_VIEW", "PATIENT", patientId, "Motivo: " + reason.trim(), ConsentService.clientIp());
        try {
            Map<String, Object> data = objectMapper.readValue(encryption.decrypt(vault.getPayload()), new TypeReference<LinkedHashMap<String, Object>>() {});
            data.put("retainUntil", vault.getRetainUntil().toString());
            return data;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el resguardo", e);
        }
    }

    /** Tarea diaria: borra los resguardos vencidos y deja anónimo el expediente. */
    @Transactional
    public int purgeExpiredVaults() {
        int count = 0;
        for (PrivacyVault v : vaultRepository.findExpired(LocalDate.now())) {
            v.setPayload(null);
            v.setPurgedAt(LocalDateTime.now());
            vaultRepository.save(v);
            Patient p = v.getPatient();
            anonymizeClinicalText(p);
            p.setLastName(p.getLastName().replace("bloqueado", "anónimo"));
            p.setPrivacyStatus(ANONYMIZED);
            p.setAnonymizedAt(LocalDateTime.now());
            patientRepository.save(p);
            count++;
        }
        if (count > 0) log.info("Privacidad: {} resguardo(s) vencido(s) borrado(s)", count);
        return count;
    }

    // ── Oposición / limitación / rectificación clínica ──────────

    @Transactional
    public List<String> applyPreferences(Patient patient, Boolean reminders, Boolean news, String channel) {
        List<String> done = new ArrayList<>();
        if (reminders != null && !reminders.equals(patient.getNotifyReminders())) {
            patient.setNotifyReminders(reminders);
            consentService.record(patient, patient.getUser(), ConsentRecord.REMINDERS, reminders, channel);
            done.add(reminders ? "Se activaron los recordatorios de citas por correo." : "Se dejaron de enviar recordatorios de citas por correo.");
        }
        if (news != null && !news.equals(patient.getNotifyNews())) {
            patient.setNotifyNews(news);
            consentService.record(patient, patient.getUser(), ConsentRecord.NEWS, news, channel);
            done.add(news ? "Se activaron las novedades y encuestas." : "Se dejaron de enviar novedades y encuestas.");
        }
        patientRepository.save(patient);
        if (done.isEmpty()) done.add("Las preferencias ya estaban como se pidió.");
        return done;
    }

    @Transactional
    public PatientClarification addClarification(Patient patient, ArcoRequest arco, String note, User actor) {
        if (note == null || note.trim().length() < 10) throw new BusinessException("La nota aclaratoria debe tener al menos 10 caracteres");
        return clarificationRepository.save(PatientClarification.builder()
                .patient(patient).arcoRequest(arco).note(note.trim()).createdBy(actor).build());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> clarifications(Long patientId) {
        return clarificationRepository.listForPatient(patientId).stream().map(c -> Map.<String, Object>of(
                "id", c.getId(), "note", c.getNote(), "createdAt", c.getCreatedAt(),
                "createdBy", c.getCreatedBy() == null ? "" : c.getCreatedBy().getFirstName() + " " + c.getCreatedBy().getLastName())).toList();
    }

    // ── Ayudas ──────────────────────────────────────────────────

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String address(Patient p) {
        List<String> parts = new ArrayList<>();
        for (String s : new String[]{p.getAddressLine(), p.getNeighborhood(),
                (p.getPostalCode() != null ? "CP " + p.getPostalCode() + " " : "") + (p.getCity() != null ? p.getCity() : ""), p.getState()}) {
            if (s != null && !s.isBlank()) parts.add(s.trim());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String bloodLabel(String name) {
        return name.replace("_POSITIVE", "+").replace("_NEGATIVE", "-").replace("OTHER", "Otro").replace("UNKNOWN", "No sabe");
    }

    private static String sexLabel(String s) {
        if (s == null) return null;
        return switch (s) {
            case "F" -> "Femenino";
            case "M" -> "Masculino";
            default -> "Otro / prefiere no decir";
        };
    }

    private static String statusLabel(AppointmentStatus s) {
        return switch (s) {
            case SCHEDULED -> "Programada";
            case COMPLETED -> "Atendida";
            case CANCELLED -> "Cancelada";
            case NO_SHOW -> "No asistió";
        };
    }

    static String scopeLabel(String scope) {
        return switch (scope) {
            case ConsentRecord.NOTICE -> "Aviso de privacidad";
            case ConsentRecord.SENSITIVE -> "Datos de salud";
            case ConsentRecord.REMINDERS -> "Recordatorios";
            case ConsentRecord.NEWS -> "Novedades y encuestas";
            default -> scope;
        };
    }

}
