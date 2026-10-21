package com.hospital.citas.service;

import com.hospital.citas.dto.request.ArcoActions;
import com.hospital.citas.dto.request.ArcoRequestCreate;
import com.hospital.citas.dto.response.ArcoRequestResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.ArcoRequestStatus;
import com.hospital.citas.enums.ArcoRequestType;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.pdf.FlowDocument;
import com.hospital.citas.pdf.LogoLoader;
import com.hospital.citas.pdf.PdfKit;
import com.hospital.citas.repository.*;
import com.hospital.citas.security.CredentialEncryptionService;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.tenant.TenantContext;
import com.hospital.citas.util.SecureTokens;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Derechos ARCO de punta a punta, como lo exige la LFPDPPP y al estilo de Mercado Libre:
 * <ol>
 *   <li><b>Recepción.</b> Formulario público (se confirma el correo con un enlace), desde la
 *   cuenta del paciente (con su contraseña) o en mostrador. Un correo = un paciente por hospital,
 *   así que cada solicitud corresponde a un solo titular.</li>
 *   <li><b>Identidad.</b> El titular sube su identificación desde su enlace de seguimiento o la
 *   muestra en recepción; el admin registra cómo la verificó. Sin eso no se puede dar por
 *   procedente un derecho que expone o borra datos.</li>
 *   <li><b>Plazos.</b> 20 días hábiles para responder (prórroga única de 20, justificada); 10 para
 *   que el titular complete lo que se le pidió; 15 para hacerlo efectivo. Sin fines de semana ni
 *   días inhábiles oficiales.</li>
 *   <li><b>Respuesta y ejecución.</b> Procede / en parte / no procede con fundamento y constancia
 *   PDF; luego el sistema ejecuta el derecho (reporte de datos, corrección, bloqueo, preferencias).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ArcoRequestService {

    private static final Logger log = LoggerFactory.getLogger(ArcoRequestService.class);
    private static final int RESPONSE_DAYS = 20;
    private static final int INFO_DAYS = 10;
    private static final int EXECUTION_DAYS = 15;
    private static final int MAX_PER_EMAIL_PER_DAY = 3;
    private static final long MAX_UPLOAD = 5L * 1024 * 1024;
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DMY_HM = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ArcoRequestRepository arcoRequestRepository;
    private final ArcoEventRepository eventRepository;
    private final ArcoDocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final ArcoEmailService arcoEmailService;
    private final BusinessCalendar calendar;
    private final PrivacyService privacyService;
    private final PatientService patientService;
    private final ConsentService consentService;
    private final CredentialEncryptionService encryption;
    private final PasswordEncoder passwordEncoder;
    private final HospitalConfigService hospitalConfigService;
    private final LogoLoader logoLoader;

    // ══ Recepción de solicitudes ═════════════════════════════════

    /** Formulario público: queda "por confirmar correo" hasta que el titular abre el enlace. La
     * respuesta es la misma exista o no el correo (el folio llega en el correo): así el formulario
     * no sirve para averiguar si alguien es paciente del hospital. */
    @Transactional
    public ArcoRequestResponse createPublic(ArcoRequestCreate request) {
        request.requireIdentityFields();
        String email = request.getRequesterEmail().trim().toLowerCase(Locale.ROOT);
        User account = userRepository.findByEmail(email).orElse(null);
        Patient patient = patientRepository.findFirstByEmailIgnoreCaseAndIsActiveTrue(email).orElse(null);
        rateLimit(email);
        if (account == null && patient == null) return pendingEmailAck(request, email);
        if (patient == null) patient = patientRepository.findByUser_Id(account.getId()).orElse(null);
        String token = SecureTokens.newToken();
        ArcoRequest saved = arcoRequestRepository.save(base(request, request.getRequesterName().trim(), email, account, patient)
                .channel("WEB")
                .status(ArcoRequestStatus.PENDING_EMAIL)
                .trackingTokenHash(SecureTokens.hash(token))
                .trackingTokenEnc(encryption.encrypt(token))
                .dueDate(calendar.addBusinessDays(LocalDate.now(), RESPONSE_DAYS))
                .build());
        event(saved, null, "El titular", "CREATED", "Solicitud enviada desde el formulario público; falta confirmar el correo.");
        afterCommit(() -> arcoEmailService.sendConfirmEmail(saved, token));
        return pendingEmailAck(request, email);
    }

    private ArcoRequestResponse pendingEmailAck(ArcoRequestCreate request, String email) {
        return ArcoRequestResponse.builder()
                .requesterEmail(email)
                .type(request.getType().name())
                .typeLabel(request.getType().label())
                .status(ArcoRequestStatus.PENDING_EMAIL.name())
                .statusLabel(ArcoRequestStatus.PENDING_EMAIL.label())
                .build();
    }

    /** Desde la cuenta del paciente: el correo ya está verificado; con su contraseña también la identidad. */
    @Transactional
    public ArcoRequestResponse createOwn(ArcoRequestCreate request) {
        User user = requireUser();
        if (user.getHospitalId() == null) throw new BusinessException("Las solicitudes ARCO se levantan ante un hospital");
        boolean passwordOk = request.getPassword() != null && passwordEncoder.matches(request.getPassword(), user.getPasswordHash());
        if (request.getType() != null && request.getType().needsStrongIdentity() && !passwordOk) {
            throw new BusinessException("Para proteger tus datos, confirma tu contraseña actual");
        }
        rateLimit(user.getEmail().toLowerCase(Locale.ROOT));
        Patient patient = patientRepository.findByUser_Id(user.getId()).orElse(null);
        String token = SecureTokens.newToken();
        LocalDateTime now = LocalDateTime.now();
        ArcoRequest saved = arcoRequestRepository.save(base(request, user.getFirstName() + " " + user.getLastName(),
                user.getEmail().toLowerCase(Locale.ROOT), user, patient)
                .channel("ACCOUNT")
                .status(ArcoRequestStatus.PENDING)
                .trackingTokenHash(SecureTokens.hash(token))
                .trackingTokenEnc(encryption.encrypt(token))
                .receivedAt(now).emailVerifiedAt(now)
                .identityVerifiedAt(passwordOk ? now : null)
                .identityMethod(passwordOk ? "SESSION_PASSWORD" : null)
                .identityNotes(passwordOk ? "Confirmó su contraseña al enviar la solicitud desde su cuenta." : null)
                .dueDate(calendar.addBusinessDays(now.toLocalDate(), RESPONSE_DAYS))
                .build());
        event(saved, user, "El titular", "RECEIVED", "Solicitud enviada desde su cuenta" + (passwordOk ? " (identidad confirmada con su contraseña)." : "."));
        notifyReceived(saved, token);
        return toResponse(saved, null);
    }

    /** Registrada en mostrador por el admin (pacientes sin correo, menores, representantes). */
    @Transactional
    public ArcoRequestResponse createInPerson(ArcoActions.InPerson req) {
        User actor = requireUser();
        if (req.patientId() == null) throw new BusinessException("Elige al paciente titular");
        Patient patient = patientRepository.findById(req.patientId()).orElseThrow(() -> new ResourceNotFoundException("Paciente no encontrado"));
        ArcoRequestType type = parseType(req.type());
        String email = blank(req.contactEmail()) ? (patient.getEmail() == null ? "" : patient.getEmail()) : req.contactEmail().trim().toLowerCase(Locale.ROOT);
        String token = SecureTokens.newToken();
        LocalDateTime now = LocalDateTime.now();
        ArcoRequest saved = arcoRequestRepository.save(ArcoRequest.builder()
                .requesterName(patient.getFirstName() + " " + patient.getLastName())
                .requesterEmail(email)
                .requesterPhone(patient.getPhone())
                .type(type)
                .details(blank(req.details()) ? null : req.details().trim())
                .patient(patient)
                .requesterUser(patient.getUser())
                .representative(Boolean.TRUE.equals(req.representative()))
                .representativeName(blank(req.representativeName()) ? null : req.representativeName().trim())
                .channel("IN_PERSON")
                .status(ArcoRequestStatus.PENDING)
                .trackingTokenHash(SecureTokens.hash(token))
                .trackingTokenEnc(encryption.encrypt(token))
                .receivedAt(now).emailVerifiedAt(blank(email) ? null : now)
                .identityVerifiedAt(now)
                .identityMethod(Boolean.TRUE.equals(req.representative()) ? "REPRESENTATIVE" : "ID_IN_PERSON")
                .identityVerifiedBy(actor)
                .identityNotes(blank(req.identityNotes()) ? "Identificación revisada en mostrador." : req.identityNotes().trim())
                .dueDate(calendar.addBusinessDays(now.toLocalDate(), RESPONSE_DAYS))
                .build());
        event(saved, actor, null, "RECEIVED", "Solicitud registrada en mostrador; identidad revisada en persona.");
        afterCommit(() -> arcoEmailService.sendReceived(saved, token));
        return toResponse(saved, null);
    }

    private ArcoRequest.ArcoRequestBuilder base(ArcoRequestCreate request, String name, String email, User account, Patient patient) {
        return ArcoRequest.builder()
                .requesterName(name)
                .requesterEmail(email)
                .requesterPhone(blankToNull(request.getRequesterPhone()))
                .type(request.getType())
                .details(blankToNull(request.getDetails()))
                .requesterUser(account)
                .patient(patient)
                .representative(Boolean.TRUE.equals(request.getRepresentative()))
                .representativeName(blankToNull(request.getRepresentativeName()));
    }

    private void rateLimit(String email) {
        if (arcoRequestRepository.countRecentByEmail(email, LocalDateTime.now().minusDays(1)) >= MAX_PER_EMAIL_PER_DAY) {
            throw new BusinessException("Ya recibimos varias solicitudes de este correo hoy. Te responderemos a la brevedad.");
        }
    }

    // ══ Portal del titular (enlace de seguimiento) ═══════════════

    @Transactional
    public Map<String, Object> confirmEmail(String token) {
        ArcoRequest a = byToken(token);
        if (a.getStatus() == ArcoRequestStatus.PENDING_EMAIL) {
            LocalDateTime now = LocalDateTime.now();
            a.setEmailVerifiedAt(now);
            a.setReceivedAt(now);
            a.setStatus(ArcoRequestStatus.PENDING);
            a.setDueDate(calendar.addBusinessDays(now.toLocalDate(), RESPONSE_DAYS));
            arcoRequestRepository.save(a);
            event(a, null, "El titular", "RECEIVED", "El titular confirmó su correo: la solicitud se tiene por recibida y corre el plazo de 20 días hábiles.");
            notifyReceived(a, token);
        }
        return track(token);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> track(String token) {
        ArcoRequest a = byToken(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request", toResponse(a, null));
        out.put("events", eventRepository.timeline(a.getId()).stream().map(this::publicEvent).toList());
        out.put("documents", documentRepository.listActive(a.getId()).stream()
                .filter(d -> ArcoDocument.DELIVERY.equals(d.getKind()) || ArcoDocument.CONSTANCIA.equals(d.getKind())
                        || ArcoDocument.IDENTITY.equals(d.getKind()) || ArcoDocument.INFO.equals(d.getKind()))
                .map(d -> docMeta(d, !ArcoDocument.IDENTITY.equals(d.getKind()) && !ArcoDocument.INFO.equals(d.getKind()))).toList());
        out.put("canUploadIdentity", a.getStatus().isOpen() && a.getIdentityVerifiedAt() == null);
        out.put("canAnswerInfo", a.getStatus() == ArcoRequestStatus.INFO_REQUESTED);
        return out;
    }

    @Transactional
    public Map<String, Object> uploadIdentity(String token, MultipartFile file) {
        ArcoRequest a = byToken(token);
        if (!a.getStatus().isOpen() && a.getStatus() != ArcoRequestStatus.PENDING_EMAIL) {
            throw new BusinessException("Esta solicitud ya está cerrada");
        }
        storeDocument(a, ArcoDocument.IDENTITY, file);
        event(a, null, "El titular", "IDENTITY_UPLOADED", "El titular subió una identificación para verificar su identidad.");
        return track(token);
    }

    @Transactional
    public Map<String, Object> answerInfo(String token, String message, MultipartFile file) {
        ArcoRequest a = byToken(token);
        if (a.getStatus() != ArcoRequestStatus.INFO_REQUESTED) throw new BusinessException("No hay un requerimiento pendiente en esta solicitud");
        if (blank(message) && (file == null || file.isEmpty())) throw new BusinessException("Escribe tu respuesta o adjunta el documento que se te pidió");
        if (file != null && !file.isEmpty()) storeDocument(a, ArcoDocument.INFO, file);
        a.setInfoResponse(blank(message) ? "(Adjuntó un documento)" : message.trim());
        a.setStatus(ArcoRequestStatus.PENDING);
        // El plazo de respuesta vuelve a correr a partir de que se recibe lo que se pidió.
        a.setDueDate(calendar.addBusinessDays(LocalDate.now(), RESPONSE_DAYS));
        arcoRequestRepository.save(a);
        event(a, null, "El titular", "INFO_ANSWERED", "El titular respondió al requerimiento. Nueva fecha límite de respuesta: " + a.getDueDate().format(DMY) + ".");
        List<String> admins = adminEmails();
        afterCommit(() -> arcoEmailService.sendNoticeToAdmins(a, admins));
        return track(token);
    }

    @Transactional(readOnly = true)
    public ArcoDocument titularDocument(String token, Long documentId) {
        ArcoRequest a = byToken(token);
        ArcoDocument d = documentRepository.findById(documentId).orElseThrow(() -> new ResourceNotFoundException("Documento no encontrado"));
        if (!d.getArcoRequest().getId().equals(a.getId()) || d.getDeletedAt() != null
                || !(ArcoDocument.DELIVERY.equals(d.getKind()) || ArcoDocument.CONSTANCIA.equals(d.getKind()))) {
            throw new ResourceNotFoundException("Documento no encontrado");
        }
        return d;
    }

    public byte[] content(ArcoDocument d) {
        if (d.getData() == null) throw new BusinessException("Ese documento ya fue eliminado");
        return encryption.decryptBytes(d.getData());
    }

    // ══ Atención (administrador) ═════════════════════════════════

    @Transactional(readOnly = true)
    public List<ArcoRequestResponse> list(ArcoRequestStatus status) {
        return arcoRequestRepository.search(status).stream()
                .filter(a -> status != null || a.getStatus() != ArcoRequestStatus.PENDING_EMAIL)
                .map(a -> toResponse(a, null)).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        ArcoRequest a = find(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request", toResponse(a, null));
        out.put("events", eventRepository.timeline(a.getId()).stream().map(this::adminEvent).toList());
        out.put("documents", documentRepository.listActive(a.getId()).stream().map(d -> docMeta(d, true)).toList());
        if (a.getType() == ArcoRequestType.CANCELLATION
                || (a.getType() == ArcoRequestType.REVOCATION && a.getStatus().isOpen())) {
            out.put("cancellationPlan", privacyService.cancellationPlan(activePatient(a), a.getRequesterUser()));
        }
        Patient p = a.getPatient();
        if (p != null) {
            out.put("patient", Map.of("id", p.getId(), "name", p.getFirstName() + " " + p.getLastName(),
                    "privacyStatus", p.getPrivacyStatus(), "notifyReminders", p.getNotifyReminders(), "notifyNews", p.getNotifyNews(),
                    "hasAccount", p.getUser() != null));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ArcoDocument adminDocument(Long id, Long documentId) {
        ArcoDocument d = documentRepository.findById(documentId).orElseThrow(() -> new ResourceNotFoundException("Documento no encontrado"));
        if (!d.getArcoRequest().getId().equals(id) || d.getDeletedAt() != null) throw new ResourceNotFoundException("Documento no encontrado");
        return d;
    }

    @Transactional
    public ArcoRequestResponse verifyIdentity(Long id, ArcoActions.Verify req) {
        User actor = requireUser();
        ArcoRequest a = findOpen(id);
        String method = req.method() == null ? "" : req.method().trim();
        if (!List.of("ID_ONLINE", "ID_IN_PERSON", "SESSION_PASSWORD", "REPRESENTATIVE").contains(method)) {
            throw new BusinessException("Elige cómo verificaste la identidad");
        }
        if ("ID_ONLINE".equals(method) && documentRepository.listActive(a.getId()).stream().noneMatch(d -> ArcoDocument.IDENTITY.equals(d.getKind()))) {
            throw new BusinessException("El titular no ha subido una identificación; verifícala en mostrador o pídesela");
        }
        a.setIdentityVerifiedAt(LocalDateTime.now());
        a.setIdentityMethod(method);
        a.setIdentityVerifiedBy(actor);
        a.setIdentityNotes(blankToNull(req.notes()));
        arcoRequestRepository.save(a);
        event(a, actor, null, "IDENTITY_VERIFIED", "Identidad verificada: " + methodLabel(method) + (blank(req.notes()) ? "." : ". " + req.notes().trim()));
        return toResponse(a, null);
    }

    @Transactional
    public ArcoRequestResponse requestInfo(Long id, ArcoActions.RequestInfo req) {
        User actor = requireUser();
        ArcoRequest a = findOpen(id);
        if (a.getStatus() != ArcoRequestStatus.PENDING) throw new BusinessException("Solo se pide información a una solicitud recibida y sin responder");
        if (blank(req.message()) || req.message().trim().length() < 10) throw new BusinessException("Explica qué información o documento necesitas (mínimo 10 caracteres)");
        a.setStatus(ArcoRequestStatus.INFO_REQUESTED);
        a.setInfoRequest(req.message().trim());
        a.setInfoDue(calendar.addBusinessDays(LocalDate.now(), INFO_DAYS));
        a.setInfoResponse(null);
        arcoRequestRepository.save(a);
        event(a, actor, null, "INFO_REQUESTED", "Se pidió al titular: " + a.getInfoRequest() + " (tiene hasta el " + a.getInfoDue().format(DMY) + ").");
        String token = token(a);
        afterCommit(() -> arcoEmailService.sendInfoRequested(a, token));
        return toResponse(a, null);
    }

    @Transactional
    public ArcoRequestResponse extend(Long id, ArcoActions.Extend req) {
        User actor = requireUser();
        ArcoRequest a = findOpen(id);
        if (a.getStatus() != ArcoRequestStatus.PENDING) throw new BusinessException("Solo se amplía el plazo de respuesta de una solicitud sin responder");
        if (Boolean.TRUE.equals(a.getExtended())) throw new BusinessException("El plazo ya se amplió una vez; la ley solo permite una prórroga");
        if (blank(req.reason()) || req.reason().trim().length() < 10) throw new BusinessException("Justifica la prórroga (mínimo 10 caracteres)");
        a.setExtended(true);
        a.setExtensionReason(req.reason().trim());
        a.setDueDate(calendar.addBusinessDays(a.getDueDate(), RESPONSE_DAYS));
        arcoRequestRepository.save(a);
        event(a, actor, null, "EXTENDED", "Plazo ampliado una vez: " + a.getExtensionReason() + ". Nueva fecha límite: " + a.getDueDate().format(DMY) + ".");
        String token = token(a);
        afterCommit(() -> arcoEmailService.sendExtended(a, token));
        return toResponse(a, null);
    }

    @Transactional
    public ArcoRequestResponse respond(Long id, ArcoActions.Respond req) {
        User actor = requireUser();
        ArcoRequest a = findOpen(id);
        if (a.getStatus() != ArcoRequestStatus.PENDING) {
            throw new BusinessException(a.getStatus() == ArcoRequestStatus.INFO_REQUESTED
                    ? "Espera a que el titular conteste lo que se le pidió (o a que venza su plazo)" : "Esta solicitud ya se respondió");
        }
        String outcome = req.outcome() == null ? "" : req.outcome().trim().toUpperCase(Locale.ROOT);
        if (!List.of("PROCEDENTE", "PARCIAL", "IMPROCEDENTE").contains(outcome)) throw new BusinessException("Elige si la solicitud procede, procede en parte o no procede");
        boolean rejected = outcome.equals("IMPROCEDENTE");
        if ((rejected || outcome.equals("PARCIAL")) && (blank(req.legalBasis()) || req.legalBasis().trim().length() < 15)) {
            throw new BusinessException("Escribe el fundamento y motivo de la respuesta (mínimo 15 caracteres): es obligatorio cuando no procede o procede en parte");
        }
        if (!rejected && a.getType().needsStrongIdentity() && a.getIdentityVerifiedAt() == null) {
            throw new BusinessException("Antes de dar por procedente este derecho verifica la identidad del titular (o de su representante)");
        }
        if (!rejected && activePatient(a) == null && a.getRequesterUser() == null) {
            throw new BusinessException("No hay datos de este titular en el hospital: la respuesta debe ser que no procede");
        }
        a.setOutcome(outcome);
        a.setLegalBasis(blankToNull(req.legalBasis()));
        a.setResolutionNotes(blankToNull(req.notes()));
        a.setResolvedBy(actor);
        if (rejected) {
            a.setStatus(ArcoRequestStatus.REJECTED);
            a.setResolvedAt(LocalDateTime.now());
        } else {
            a.setStatus(ArcoRequestStatus.APPROVED);
            a.setExecutionDue(calendar.addBusinessDays(LocalDate.now(), EXECUTION_DAYS));
        }
        arcoRequestRepository.save(a);
        event(a, actor, null, "ANSWERED", "Respuesta: " + outcomeLabel(outcome) + (a.getLegalBasis() == null ? "." : ". Fundamento: " + a.getLegalBasis())
                + (rejected ? "" : " Se hará efectiva a más tardar el " + a.getExecutionDue().format(DMY) + "."));
        byte[] constancia = storeConstancia(a);
        String token = token(a);
        afterCommit(() -> arcoEmailService.sendAnswer(a, token, constancia));
        return toResponse(a, null);
    }

    /** Hace efectivo el derecho. Lo que se hizo queda en la constancia y en la línea de tiempo. */
    @Transactional
    public ArcoRequestResponse execute(Long id, ArcoActions.Execute req) {
        User actor = requireUser();
        ArcoRequest a = find(id);
        if (a.getStatus() != ArcoRequestStatus.APPROVED) throw new BusinessException("Primero responde la solicitud como procedente");
        Patient patient = activePatient(a);
        List<String> done = new ArrayList<>();
        switch (a.getType()) {
            case ACCESS -> {
                byte[] report = privacyService.dataReport(patient, a.getRequesterUser(), folio(a));
                saveDocument(a, ArcoDocument.DELIVERY, "datos-personales-" + folio(a) + ".pdf", "application/pdf", report);
                done.add("Se generó el reporte de datos personales y quedó disponible para el titular en su enlace de seguimiento.");
            }
            case RECTIFICATION -> {
                if (req.profile() == null && blank(req.clarification())) {
                    throw new BusinessException("Captura los datos corregidos o una nota aclaratoria");
                }
                if (patient == null) throw new BusinessException("Esta solicitud no tiene un expediente de paciente ligado");
                if (req.profile() != null) {
                    String before = summary(patient);
                    patientService.updateProfile(patient.getId(), req.profile());
                    Patient after = patientRepository.findById(patient.getId()).orElseThrow();
                    done.add("Se corrigieron los datos personales. Antes: " + before + ". Ahora: " + summary(after) + ".");
                }
                if (!blank(req.clarification())) {
                    privacyService.addClarification(patient, a, req.clarification(), actor);
                    done.add("Se agregó una nota aclaratoria al expediente: " + req.clarification().trim());
                }
            }
            case CANCELLATION -> done.addAll(privacyService.executeCancellation(patient, a.getRequesterUser(), a, actor));
            case OPPOSITION, LIMITATION -> {
                if (patient == null) throw new BusinessException("Esta solicitud no tiene un expediente de paciente ligado");
                if (req.reminders() == null && req.news() == null && blank(req.notes())) {
                    throw new BusinessException("Indica qué uso se deja de hacer (recordatorios, novedades) o describe lo que se hizo");
                }
                if (req.reminders() != null || req.news() != null) {
                    done.addAll(privacyService.applyPreferences(patient, req.reminders(), req.news(), "ARCO"));
                }
            }
            case REVOCATION -> {
                if ("ALL".equalsIgnoreCase(req.revocationScope())) {
                    done.add("Se revocó el consentimiento para todas las finalidades: se aplicó la cancelación de los datos.");
                    done.addAll(privacyService.executeCancellation(patient, a.getRequesterUser(), a, actor));
                } else {
                    if (patient == null) throw new BusinessException("Esta solicitud no tiene un expediente de paciente ligado");
                    done.add("Se revocó el consentimiento para las finalidades secundarias.");
                    done.addAll(privacyService.applyPreferences(patient, false, false, "ARCO"));
                }
            }
        }
        if (!blank(req.notes())) done.add(req.notes().trim());
        a.setStatus(ArcoRequestStatus.COMPLETED);
        a.setExecutedAt(LocalDateTime.now());
        a.setExecutedBy(actor);
        a.setExecutionSummary(String.join("\n", done));
        a.setResolvedAt(LocalDateTime.now());
        arcoRequestRepository.save(a);
        event(a, actor, null, "EXECUTED", "Derecho hecho efectivo: " + String.join(" ", done));
        byte[] constancia = storeConstancia(a);
        String token = token(a);
        afterCommit(() -> arcoEmailService.sendExecuted(a, token, constancia));
        return toResponse(a, null);
    }

    @Transactional(readOnly = true)
    public byte[] constancia(Long id) {
        return documentRepository.findFirstByArcoRequest_IdAndKindAndDeletedAtIsNullOrderByUploadedAtDesc(id, ArcoDocument.CONSTANCIA)
                .map(this::content).orElseGet(() -> buildConstancia(find(id)));
    }

    // ══ Paciente con cuenta y plataforma ═════════════════════════

    @Transactional(readOnly = true)
    public List<ArcoRequestResponse> listMine() {
        User user = requireUser();
        return arcoRequestRepository.findByRequesterUser_IdOrderByCreatedAtDesc(user.getId()).stream()
                .map(a -> toResponse(a, null)).toList();
    }

    /** Enlace de seguimiento de una solicitud propia (para abrir el portal desde "Mis solicitudes"). */
    @Transactional(readOnly = true)
    public String myTrackingToken(Long id) {
        User user = requireUser();
        ArcoRequest a = find(id);
        if (a.getRequesterUser() == null || !a.getRequesterUser().getId().equals(user.getId())) throw new ResourceNotFoundException("Solicitud no encontrada");
        return token(a);
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return arcoRequestRepository.countByStatus(ArcoRequestStatus.PENDING)
                + arcoRequestRepository.countByStatus(ArcoRequestStatus.APPROVED);
    }

    public List<ArcoRequestResponse> listAllHospitals(ArcoRequestStatus status) {
        return TenantContext.callAs(TenantContext.ROOT, () -> {
            Map<Long, Hospital> hospitals = new HashMap<>();
            hospitalRepository.findAll().forEach(h -> hospitals.put(h.getId(), h));
            return arcoRequestRepository.search(status).stream()
                    .filter(a -> status != null || a.getStatus() != ArcoRequestStatus.PENDING_EMAIL)
                    .map(a -> toResponse(a, hospitals.get(a.getHospitalId())))
                    .toList();
        });
    }

    // ══ Mantenimiento diario (TenantJobs, por hospital) ══════════

    @Transactional
    public void maintenance() {
        LocalDateTime now = LocalDateTime.now();
        for (ArcoRequest a : arcoRequestRepository.search(ArcoRequestStatus.PENDING_EMAIL)) {
            if (a.getCreatedAt().isBefore(now.minusDays(3))) {
                eventRepository.deleteAll(eventRepository.timeline(a.getId()));
                documentRepository.deleteAll(documentRepository.listActive(a.getId()));
                arcoRequestRepository.delete(a);
            }
        }
        for (ArcoRequest a : arcoRequestRepository.search(ArcoRequestStatus.INFO_REQUESTED)) {
            if (a.getInfoDue() != null && a.getInfoDue().isBefore(LocalDate.now())) {
                a.setStatus(ArcoRequestStatus.NOT_FILED);
                a.setResolvedAt(now);
                arcoRequestRepository.save(a);
                event(a, null, "Sistema", "NOT_FILED", "El titular no respondió al requerimiento dentro de los 10 días hábiles: se tiene por no presentada.");
                afterCommit(() -> arcoEmailService.sendNotFiled(a));
            }
        }
        // Minimización: las identificaciones se borran 30 días después de cerrar la solicitud.
        for (ArcoDocument d : documentRepository.findIdentityDocsClosedBefore(now.minusDays(30))) {
            d.setData(null);
            d.setDeletedAt(now);
            documentRepository.save(d);
        }
        privacyService.purgeExpiredVaults();
    }

    // ══ Constancia PDF ═══════════════════════════════════════════

    private byte[] storeConstancia(ArcoRequest a) {
        byte[] pdf = buildConstancia(a);
        // La constancia anterior (de la respuesta) se reemplaza por la nueva; se conserva, pero ya no se muestra.
        documentRepository.listActive(a.getId()).stream().filter(d -> ArcoDocument.CONSTANCIA.equals(d.getKind())).forEach(d -> {
            d.setDeletedAt(LocalDateTime.now());
            documentRepository.save(d);
        });
        saveDocument(a, ArcoDocument.CONSTANCIA, "constancia-" + folio(a) + ".pdf", "application/pdf", pdf);
        return pdf;
    }

    private byte[] buildConstancia(ArcoRequest a) {
        HospitalConfig h = hospitalConfigService.getEntity();
        try (FlowDocument d = new FlowDocument(h, logoLoader, "Derechos ARCO", "Constancia de atención " + folio(a),
                a.getType().label() + " · " + a.getStatus().label())) {
            d.section("Solicitud").fields(List.of(
                    new String[]{"Titular", a.getRequesterName()},
                    new String[]{"Representante", Boolean.TRUE.equals(a.getRepresentative()) ? a.getRepresentativeName() : "No aplica"},
                    new String[]{"Derecho", a.getType().label()},
                    new String[]{"Medio", channelLabel(a.getChannel())},
                    new String[]{"Recibida", a.getReceivedAt() == null ? null : a.getReceivedAt().format(DMY_HM)},
                    new String[]{"Fecha límite de respuesta", a.getDueDate().format(DMY) + (Boolean.TRUE.equals(a.getExtended()) ? " (con prórroga)" : "")},
                    new String[]{"Identidad", a.getIdentityVerifiedAt() == null ? "Sin verificar"
                            : methodLabel(a.getIdentityMethod()) + " · " + a.getIdentityVerifiedAt().format(DMY_HM)},
                    new String[]{"Lo que se pidió", a.getDetails()}));
            if (a.getOutcome() != null) {
                boolean rejected = "IMPROCEDENTE".equals(a.getOutcome());
                d.section("Respuesta");
                d.callout(outcomeLabel(a.getOutcome()), (a.getLegalBasis() == null ? "" : "Fundamento y motivo: " + a.getLegalBasis() + " ")
                        + (a.getResolutionNotes() == null ? "" : a.getResolutionNotes()),
                        rejected ? PdfKit.RED_BG : PdfKit.GREEN_BG, rejected ? PdfKit.RED : PdfKit.GREEN);
                if (!rejected && a.getExecutionDue() != null && a.getExecutedAt() == null) {
                    d.paragraph("Se hará efectiva a más tardar el " + a.getExecutionDue().format(DMY) + " (15 días hábiles).");
                }
            }
            if (a.getExecutionSummary() != null) {
                d.section("Lo que se hizo (" + a.getExecutedAt().format(DMY_HM) + ")");
                d.bullets(Arrays.asList(a.getExecutionSummary().split("\n")));
            }
            d.section("Línea de tiempo");
            d.bullets(eventRepository.timeline(a.getId()).stream()
                    .map(e -> e.getCreatedAt().format(DMY_HM) + " · " + e.getMessage()).toList());
            d.note("Si no estás de acuerdo con la respuesta, puedes acudir a la Secretaría Anticorrupción y Buen Gobierno "
                    + "dentro de los plazos que marca la Ley Federal de Protección de Datos Personales en Posesión de los Particulares. "
                    + "Responsable de los datos: " + (h.getLegalName() != null ? h.getLegalName() : h.getName())
                    + (h.getPrivacyContactEmail() != null ? " · contacto de datos personales: " + h.getPrivacyContactEmail() : "") + ".");
            return d.finish();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo generar la constancia", e);
        }
    }

    // ══ Ayudas ═══════════════════════════════════════════════════

    private void notifyReceived(ArcoRequest a, String token) {
        List<String> admins = adminEmails();
        afterCommit(() -> {
            arcoEmailService.sendReceived(a, token);
            arcoEmailService.sendNoticeToAdmins(a, admins);
        });
    }

    private List<String> adminEmails() {
        return userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN).stream().map(User::getEmail).filter(Objects::nonNull).toList();
    }

    private void storeDocument(ArcoRequest a, String kind, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("Adjunta el archivo");
        if (file.getSize() > MAX_UPLOAD) throw new BusinessException("El archivo no debe pasar de 5 MB");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new BusinessException("No se pudo leer el archivo");
        }
        String type = sniff(bytes);
        if (type == null) throw new BusinessException("Sube una foto (JPG o PNG) o un PDF de tu identificación");
        String ext = type.equals("application/pdf") ? "pdf" : type.equals("image/png") ? "png" : "jpg";
        saveDocument(a, kind, kind.toLowerCase(Locale.ROOT) + "-" + folio(a) + "." + ext, type, bytes);
    }

    private void saveDocument(ArcoRequest a, String kind, String name, String type, byte[] bytes) {
        documentRepository.save(ArcoDocument.builder()
                .arcoRequest(a).kind(kind).filename(name).contentType(type).sizeBytes(bytes.length)
                .data(encryption.encryptBytes(bytes)).build());
    }

    /** Tipo real del archivo por sus primeros bytes (no por la extensión que manda el navegador). */
    static String sniff(byte[] b) {
        if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        return null;
    }

    private Patient activePatient(ArcoRequest a) {
        if (a.getPatient() == null) return null;
        return patientRepository.findById(a.getPatient().getId()).filter(Patient::isPrivacyActive).orElse(null);
    }

    private ArcoRequest byToken(String token) {
        if (token == null || token.length() < 20) throw new ResourceNotFoundException("Enlace no válido");
        return arcoRequestRepository.findByTrackingTokenHash(SecureTokens.hash(token))
                .orElseThrow(() -> new ResourceNotFoundException("El enlace no es válido o la solicitud ya no existe"));
    }

    private String token(ArcoRequest a) {
        return a.getTrackingTokenEnc() == null ? null : encryption.decrypt(a.getTrackingTokenEnc());
    }

    private ArcoRequest find(Long id) {
        return arcoRequestRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada"));
    }

    private ArcoRequest findOpen(Long id) {
        ArcoRequest a = find(id);
        if (!a.getStatus().isOpen()) throw new BusinessException("Esta solicitud ya está cerrada");
        return a;
    }

    private void event(ArcoRequest a, User actor, String label, String type, String message) {
        eventRepository.save(ArcoEvent.builder().arcoRequest(a).actor(actor)
                .actorLabel(label != null ? label : actor != null ? actor.getFirstName() + " " + actor.getLastName() : "Sistema")
                .type(type).message(message).build());
    }

    private Map<String, Object> publicEvent(ArcoEvent e) {
        return Map.of("at", e.getCreatedAt(), "type", e.getType(), "message", e.getMessage(),
                "actor", e.getActor() != null ? "Hospital" : e.getActorLabel());
    }

    private Map<String, Object> adminEvent(ArcoEvent e) {
        return Map.of("at", e.getCreatedAt(), "type", e.getType(), "message", e.getMessage(), "actor", e.getActorLabel());
    }

    private Map<String, Object> docMeta(ArcoDocument d, boolean downloadable) {
        return Map.of("id", d.getId(), "kind", d.getKind(), "filename", d.getFilename(), "contentType", d.getContentType(),
                "size", d.getSizeBytes(), "uploadedAt", d.getUploadedAt(), "downloadable", downloadable);
    }

    private static String summary(Patient p) {
        return p.getFirstName() + " " + p.getLastName() + ", tel. " + p.getPhone() + (p.getEmail() != null ? ", " + p.getEmail() : "");
    }

    private static ArcoRequestType parseType(String type) {
        try {
            return ArcoRequestType.valueOf(type);
        } catch (Exception e) {
            throw new BusinessException("Elige el derecho que se ejerce");
        }
    }

    public static String folio(ArcoRequest arco) {
        return "ARCO-" + String.format("%06d", arco.getId());
    }

    static String methodLabel(String m) {
        if (m == null) return null;
        return switch (m) {
            case "ID_ONLINE" -> "Identificación oficial enviada en línea";
            case "ID_IN_PERSON" -> "Identificación oficial revisada en mostrador";
            case "SESSION_PASSWORD" -> "Sesión iniciada y contraseña confirmada";
            case "REPRESENTATIVE" -> "Representante acreditado con documento";
            default -> m;
        };
    }

    static String outcomeLabel(String o) {
        if (o == null) return null;
        return switch (o) {
            case "PROCEDENTE" -> "Procede";
            case "PARCIAL" -> "Procede en parte";
            case "IMPROCEDENTE" -> "No procede";
            default -> o;
        };
    }

    private static String channelLabel(String c) {
        return switch (c == null ? "" : c) {
            case "ACCOUNT" -> "Cuenta del paciente";
            case "IN_PERSON" -> "Mostrador";
            default -> "Formulario en línea";
        };
    }

    private ArcoRequestResponse toResponse(ArcoRequest a, Hospital hospital) {
        LocalDate next = switch (a.getStatus()) {
            case PENDING -> a.getDueDate();
            case INFO_REQUESTED -> a.getInfoDue();
            case APPROVED -> a.getExecutionDue();
            default -> null;
        };
        String nextLabel = switch (a.getStatus()) {
            case PENDING -> "Responder antes del";
            case INFO_REQUESTED -> "El titular responde antes del";
            case APPROVED -> "Ejecutar antes del";
            default -> null;
        };
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
                .overdue(next != null && LocalDate.now().isAfter(next) && a.getStatus() != ArcoRequestStatus.INFO_REQUESTED)
                .resolvedAt(a.getResolvedAt())
                .resolvedByName(a.getResolvedBy() != null ? a.getResolvedBy().getFirstName() + " " + a.getResolvedBy().getLastName() : null)
                .hasPatientRecord(a.getPatient() != null)
                .hasAccount(a.getRequesterUser() != null)
                .hospitalName(hospital != null ? hospital.getName() : null)
                .hospitalSlug(hospital != null ? hospital.getSlug() : null)
                .channel(a.getChannel())
                .receivedAt(a.getReceivedAt())
                .emailVerified(a.getEmailVerifiedAt() != null)
                .representative(Boolean.TRUE.equals(a.getRepresentative()))
                .representativeName(a.getRepresentativeName())
                .identityVerifiedAt(a.getIdentityVerifiedAt())
                .identityMethod(a.getIdentityMethod())
                .identityMethodLabel(methodLabel(a.getIdentityMethod()))
                .identityVerifiedByName(a.getIdentityVerifiedBy() != null
                        ? a.getIdentityVerifiedBy().getFirstName() + " " + a.getIdentityVerifiedBy().getLastName() : null)
                .identityNotes(a.getIdentityNotes())
                .identityRequired(a.getType().needsStrongIdentity() && a.getIdentityVerifiedAt() == null)
                .infoRequest(a.getInfoRequest())
                .infoDue(a.getInfoDue())
                .infoResponse(a.getInfoResponse())
                .extended(Boolean.TRUE.equals(a.getExtended()))
                .extensionReason(a.getExtensionReason())
                .outcome(a.getOutcome())
                .outcomeLabel(outcomeLabel(a.getOutcome()))
                .legalBasis(a.getLegalBasis())
                .executionDue(a.getExecutionDue())
                .executedAt(a.getExecutedAt())
                .executedByName(a.getExecutedBy() != null ? a.getExecutedBy().getFirstName() + " " + a.getExecutedBy().getLastName() : null)
                .executionSummary(a.getExecutionSummary())
                .patientId(a.getPatient() != null ? a.getPatient().getId() : null)
                .patientName(a.getPatient() != null ? a.getPatient().getFirstName() + " " + a.getPatient().getLastName() : null)
                .open(a.getStatus().isOpen())
                .nextDeadline(next)
                .nextDeadlineLabel(nextLabel)
                .build();
    }

    private User requireUser() {
        User user = SecurityUtils.getCurrentUserOrNull();
        if (user == null) throw new ResourceNotFoundException("No hay sesión activa");
        return user;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    /** Los correos salen solo si la transacción se guardó. */
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
