package com.hospital.citas.service;

import com.hospital.citas.dto.request.ArcoRequestCreate;
import com.hospital.citas.dto.request.ArcoRequestResolve;
import com.hospital.citas.dto.response.ArcoRequestResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.ArcoRequestStatus;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.repository.*;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Solicitudes ARCO (Acceso, Rectificación, Cancelación, Oposición) de un hospital — patrón de
 * DemoPV, adaptado a que aquí cada hospital es el responsable de los datos de sus pacientes:
 * <ul>
 *   <li>Formulario público por hospital (sin sesión): solo se acepta si el correo corresponde a
 *   una cuenta o a un paciente que ese hospital tiene registrado.</li>
 *   <li>El paciente con cuenta las levanta y les da seguimiento desde "Mis solicitudes".</li>
 *   <li>El ADMIN del hospital las atiende; para Acceso puede descargar los datos del paciente.</li>
 *   <li>El SUPER_ADMIN ve las de todos los hospitales (supervisión de la plataforma).</li>
 * </ul>
 * Plazo legal: 20 días hábiles para responder; se guarda la fecha límite y el panel marca las vencidas.
 */
@Service
@RequiredArgsConstructor
public class ArcoRequestService {

    private static final Logger log = LoggerFactory.getLogger(ArcoRequestService.class);
    private static final int RESPONSE_BUSINESS_DAYS = 20;
    /** Tope por correo y día: frena que alguien use el formulario para inundar al hospital. */
    private static final int MAX_PER_EMAIL_PER_DAY = 3;

    private final ArcoRequestRepository arcoRequestRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final HospitalRepository hospitalRepository;
    private final ArcoEmailService arcoEmailService;

    /** Formulario público del hospital. */
    @Transactional
    public ArcoRequestResponse createPublic(ArcoRequestCreate request) {
        request.requireIdentityFields();
        String email = request.getRequesterEmail().trim().toLowerCase(Locale.ROOT);
        User account = userRepository.findByEmail(email).orElse(null);
        List<Patient> patients = patientRepository.findByEmailIgnoreCase(email);
        if (account == null && patients.isEmpty()) {
            throw new BusinessException("Ese correo no corresponde a ninguna cuenta ni paciente registrado en este hospital. "
                    + "Usa el correo que diste al registrarte o al agendar.");
        }
        return create(request, request.getRequesterName().trim(), email, account,
                patients.isEmpty() ? null : patients.get(0));
    }

    /** Paciente (o personal) con sesión: nombre y correo salen de su cuenta. */
    @Transactional
    public ArcoRequestResponse createOwn(ArcoRequestCreate request) {
        User user = requireUser();
        if (user.getHospitalId() == null) {
            throw new BusinessException("Las solicitudes ARCO se levantan ante un hospital");
        }
        Patient patient = patientRepository.findByUser_Id(user.getId()).orElse(null);
        return create(request, user.getFirstName() + " " + user.getLastName(), user.getEmail().toLowerCase(Locale.ROOT),
                user, patient);
    }

    private ArcoRequestResponse create(ArcoRequestCreate request, String name, String email, User account, Patient patient) {
        if (arcoRequestRepository.countRecentByEmail(email, LocalDateTime.now().minusDays(1)) >= MAX_PER_EMAIL_PER_DAY) {
            throw new BusinessException("Ya recibimos varias solicitudes de este correo hoy. Te responderemos a la brevedad.");
        }
        LocalDateTime now = LocalDateTime.now();
        ArcoRequest saved = arcoRequestRepository.save(ArcoRequest.builder()
                .requesterName(name)
                .requesterEmail(email)
                .requesterPhone(blankToNull(request.getRequesterPhone()))
                .type(request.getType())
                .details(blankToNull(request.getDetails()))
                .requesterUser(account)
                .patient(patient)
                .createdAt(now)
                .dueDate(addBusinessDays(now.toLocalDate(), RESPONSE_BUSINESS_DAYS))
                .build());
        log.info("Solicitud ARCO {} ({}) registrada en el hospital {}", saved.getId(), saved.getType(), saved.getHospitalId());

        List<String> adminEmails = userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN).stream()
                .map(User::getEmail).filter(Objects::nonNull).toList();
        afterCommit(() -> {
            arcoEmailService.sendReceivedToRequester(saved);
            arcoEmailService.sendNoticeToAdmins(saved, adminEmails);
        });
        return toResponse(saved, null);
    }

    @Transactional(readOnly = true)
    public List<ArcoRequestResponse> listMine() {
        User user = requireUser();
        return arcoRequestRepository.findByRequesterUser_IdOrderByCreatedAtDesc(user.getId()).stream()
                .map(a -> toResponse(a, null)).toList();
    }

    @Transactional(readOnly = true)
    public List<ArcoRequestResponse> list(ArcoRequestStatus status) {
        return arcoRequestRepository.search(status).stream().map(a -> toResponse(a, null)).toList();
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return arcoRequestRepository.countByStatus(ArcoRequestStatus.PENDING);
    }

    @Transactional
    public ArcoRequestResponse resolve(Long id, ArcoRequestResolve request) {
        User actor = requireUser();
        ArcoRequest arco = arcoRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada"));
        if (arco.getStatus() != ArcoRequestStatus.PENDING) {
            throw new BusinessException("Esta solicitud ya fue resuelta");
        }
        arco.setStatus(request.getStatus());
        arco.setResolutionNotes(request.getNotes().trim());
        arco.setResolvedBy(actor);
        arco.setResolvedAt(LocalDateTime.now());
        ArcoRequest saved = arcoRequestRepository.save(arco);
        afterCommit(() -> arcoEmailService.sendResolvedToRequester(saved));
        return toResponse(saved, null);
    }

    /**
     * Derecho de Acceso: los datos personales que el hospital guarda de quien la pidió (cuenta,
     * registros de paciente con ese correo, citas y recetas), listos para entregárselos.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> exportData(Long id) {
        ArcoRequest arco = arcoRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("folio", folio(arco));
        out.put("generadoEl", LocalDateTime.now());
        out.put("solicitante", Map.of("nombre", arco.getRequesterName(), "correo", arco.getRequesterEmail()));

        userRepository.findByEmail(arco.getRequesterEmail()).ifPresent(u -> {
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("nombre", u.getFirstName());
            account.put("apellido", u.getLastName());
            account.put("correo", u.getEmail());
            account.put("telefono", u.getPhone());
            account.put("rol", u.getRole().getName().name());
            out.put("cuenta", account);
        });

        List<Map<String, Object>> patientRecords = new ArrayList<>();
        for (Patient p : patientRepository.findByEmailIgnoreCase(arco.getRequesterEmail())) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("nombre", p.getFirstName());
            record.put("apellido", p.getLastName());
            record.put("telefono", p.getPhone());
            record.put("correo", p.getEmail());
            record.put("alergias", p.getAllergies());
            record.put("tipoDeSangre", p.getBloodType() != null ? p.getBloodType().name() : null);
            List<Map<String, Object>> appointments = new ArrayList<>();
            for (Appointment a : appointmentRepository.findByPatient_IdOrderByAppointmentDateDescStartTimeDesc(p.getId())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fecha", a.getAppointmentDate());
                item.put("hora", a.getStartTime());
                item.put("doctor", a.getDoctor().getUser().getFirstName() + " " + a.getDoctor().getUser().getLastName());
                item.put("especialidad", a.getDoctor().getSpecialty().getName());
                item.put("sede", a.getBranch().getName());
                item.put("estado", a.getStatus().name());
                item.put("motivo", a.getReasonForVisit());
                appointments.add(item);
            }
            record.put("citas", appointments);
            List<Map<String, Object>> prescriptions = new ArrayList<>();
            for (Prescription rx : prescriptionRepository.findByAppointment_Patient_IdWithDetails(p.getId())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fechaCita", rx.getAppointment().getAppointmentDate());
                item.put("diagnostico", rx.getDiagnosis());
                item.put("indicaciones", rx.getContent());
                item.put("anulada", rx.getVoidedAt() != null);
                prescriptions.add(item);
            }
            record.put("recetas", prescriptions);
            patientRecords.add(record);
        }
        out.put("registrosDePaciente", patientRecords);
        return out;
    }

    /** Vista de la plataforma: todas las solicitudes de todos los hospitales (solo lectura). */
    public List<ArcoRequestResponse> listAllHospitals(ArcoRequestStatus status) {
        return TenantContext.callAs(TenantContext.ROOT, () -> {
            Map<Long, Hospital> hospitals = new HashMap<>();
            hospitalRepository.findAll().forEach(h -> hospitals.put(h.getId(), h));
            return arcoRequestRepository.search(status).stream()
                    .map(a -> toResponse(a, hospitals.get(a.getHospitalId())))
                    .toList();
        });
    }

    // ── Helpers ─────────────────────────────────────────────────

    static LocalDate addBusinessDays(LocalDate from, int businessDays) {
        LocalDate date = from;
        int added = 0;
        while (added < businessDays) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return date;
    }

    public static String folio(ArcoRequest arco) {
        return "ARCO-" + String.format("%06d", arco.getId());
    }

    private ArcoRequestResponse toResponse(ArcoRequest a, Hospital hospital) {
        return ArcoRequestResponse.builder()
                .id(a.getId())
                .folio(folio(a))
                .requesterName(a.getRequesterName())
                .requesterEmail(a.getRequesterEmail())
                .requesterPhone(a.getRequesterPhone())
                .type(a.getType().name())
                .typeLabel(a.getType().label())
                .status(a.getStatus().name())
                .statusLabel(a.getStatus().label())
                .details(a.getDetails())
                .resolutionNotes(a.getResolutionNotes())
                .createdAt(a.getCreatedAt())
                .dueDate(a.getDueDate())
                .overdue(a.getStatus() == ArcoRequestStatus.PENDING && LocalDate.now().isAfter(a.getDueDate()))
                .resolvedAt(a.getResolvedAt())
                .resolvedByName(a.getResolvedBy() != null
                        ? a.getResolvedBy().getFirstName() + " " + a.getResolvedBy().getLastName() : null)
                .hasPatientRecord(a.getPatient() != null)
                .hasAccount(a.getRequesterUser() != null)
                .hospitalName(hospital != null ? hospital.getName() : null)
                .hospitalSlug(hospital != null ? hospital.getSlug() : null)
                .build();
    }

    private User requireUser() {
        User user = SecurityUtils.getCurrentUserOrNull();
        if (user == null) {
            throw new ResourceNotFoundException("No hay sesión activa");
        }
        return user;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Los correos salen solo si la solicitud quedó guardada. */
    private static void afterCommit(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (Exception e) {
                log.warn("Falló el envío de un correo de solicitud ARCO", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
