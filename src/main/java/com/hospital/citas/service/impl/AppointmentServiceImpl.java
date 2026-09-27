package com.hospital.citas.service.impl;

import com.hospital.citas.dto.request.*;
import com.hospital.citas.dto.response.AppointmentResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.exception.SlotUnavailableException;
import com.hospital.citas.mapper.AppointmentMapper;
import com.hospital.citas.repository.*;
import com.hospital.citas.security.DoctorScope;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.AppointmentReceiptService;
import com.hospital.citas.service.AppointmentService;
import com.hospital.citas.service.AppointmentSpecifications;
import com.hospital.citas.service.AvailabilityService;
import com.hospital.citas.service.EmailService;
import com.hospital.citas.service.NotificationService;
import com.hospital.citas.service.PatientService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Núcleo del sistema: agenda y cancela citas aplicando la regla de negocio central (ver
 * README): un slot (doctor+fecha+hora) está ocupado si existe una cita SCHEDULED/COMPLETED/
 * NO_SHOW en él, o una CANCELLED con slotReleased=false; está libre en cualquier otro caso.
 * <p>
 * Seguridad de concurrencia en el agendado, en tres capas: (1) bloqueo pesimista sobre
 * cualquier fila ocupante que se TRASLAPE con el rango pedido ANTES de insertar (ver
 * {@code AppointmentRepository.lockOccupantsForSlot}); (2) para el caso "primera reserva del
 * slot" (donde no hay fila que bloquear), un índice único parcial y una restricción de
 * exclusión de traslape en BD (ver db/01_schema.sql y db/17_functional_gaps.sql) cuya
 * violación se traduce a {@link SlotUnavailableException}; (3) {@code @Version} en la cita,
 * para que dos acciones distintas sobre la MISMA cita (cancelar vs. marcar atendida) no se
 * pisen en silencio.
 * <p>
 * Los correos se mandan hasta que la transacción ya hizo commit (ver {@link #afterCommit}):
 * si el guardado falla (ej. conflicto de versión), no sale un correo sobre algo que no pasó.
 */
@Service
@RequiredArgsConstructor
public class AppointmentServiceImpl implements AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentServiceImpl.class);

    private final AppointmentRepository appointmentRepository;
    private final AppointmentStatusHistoryRepository appointmentStatusHistoryRepository;
    private final DoctorRepository doctorRepository;
    private final BranchRepository branchRepository;
    private final PatientRepository patientRepository;
    private final PatientService patientService;
    private final AvailabilityService availabilityService;
    private final AppointmentMapper appointmentMapper;
    private final EmailService emailService;
    private final AppointmentReceiptService appointmentReceiptService;
    private final NotificationService notificationService;
    private final com.hospital.citas.service.PaymentService paymentService;
    private final DoctorScope doctorScope;

    @Value("${app.appointments.no-show-grace-minutes:15}")
    private int noShowGraceMinutes;
    @Value("${app.appointments.max-active-per-patient:5}")
    private int maxActivePerPatient;
    @Value("${app.appointments.max-active-per-phone:10}")
    private int maxActivePerPhone;
    @Value("${app.appointments.self-reschedule-min-hours:24}")
    private int selfRescheduleMinHours;
    @Value("${app.frontend-url:http://localhost:5175}")
    private String frontendUrl;

    @Override
    @Transactional
    public AppointmentResponse bookGuest(GuestAppointmentRequest request) {
        Doctor doctor = findActiveDoctor(request.getDoctorId());
        Branch branch = findActiveBranch(request.getBranchId());
        validateDoctorAssignedToBranch(doctor, branch);
        LocalTime endTime = availabilityService.resolveEndTimeOrThrow(
                doctor, branch, request.getAppointmentDate(), request.getStartTime());

        Patient patient = patientService.findOrCreateGuestPatient(
                request.getFirstName(), request.getLastName(), request.getPhone(), request.getEmail());
        // Invitado sin cuenta: modifiedBy nulo -- lo reportó él mismo, sin identidad
        // verificada (ver Patient#medicalInfoUpdatedBy).
        patientService.applyMedicalInfo(patient, request.getMedicalInfo(), null);

        Appointment appointment = createAndPersist(doctor, branch, patient, request.getAppointmentDate(),
                request.getStartTime(), endTime, request.getReasonForVisit(), null, false, true, true);

        notifyNewAppointment(appointment);
        return appointmentMapper.toResponse(appointment, true);
    }

    @Override
    @Transactional
    public AppointmentResponse bookSelf(AppointmentRequest request) {
        User currentUser = requireCurrentUser();
        Doctor doctor = findActiveDoctor(request.getDoctorId());
        Branch branch = findActiveBranch(request.getBranchId());
        validateDoctorAssignedToBranch(doctor, branch);
        LocalTime endTime = availabilityService.resolveEndTimeOrThrow(
                doctor, branch, request.getAppointmentDate(), request.getStartTime());

        Patient patient = patientService.findOrCreatePatientForUser(currentUser);
        // Se vuelve a capturar/actualizar en cada cita propia (no solo la primera vez), para
        // que se mantenga al día -- ver el javadoc de AppointmentRequest#medicalInfo.
        patientService.applyMedicalInfo(patient, request.getMedicalInfo(), currentUser);

        Appointment appointment = createAndPersist(doctor, branch, patient, request.getAppointmentDate(),
                request.getStartTime(), endTime, request.getReasonForVisit(), null, false, true, false);

        notifyNewAppointment(appointment);
        return appointmentMapper.toResponse(appointment, true);
    }

    @Override
    @Transactional
    public AppointmentResponse bookForPatient(ReceptionAppointmentRequest request) {
        User actingUser = requireCurrentUser();
        Doctor doctor = findActiveDoctor(request.getDoctorId());
        checkReceptionistSpecialtyAllowed(actingUser, doctor, "agendar citas");
        Branch branch = findActiveBranch(request.getBranchId());
        validateDoctorAssignedToBranch(doctor, branch);
        // Recepción atiende a quien ya está en el mostrador: sin los 30 min de anticipación.
        LocalTime endTime = availabilityService.resolveEndTimeOrThrow(
                doctor, branch, request.getAppointmentDate(), request.getStartTime(), true);

        Patient patient = patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Paciente no encontrado: " + request.getPatientId()));
        if (!Boolean.TRUE.equals(patient.getIsActive())) {
            throw new BusinessException("Ese paciente fue fusionado con otro registro; agenda con el registro vigente");
        }

        Appointment appointment = createAndPersist(doctor, branch, patient, request.getAppointmentDate(),
                request.getStartTime(), endTime, request.getReasonForVisit(), actingUser,
                Boolean.TRUE.equals(request.getOverbook()), false, false);

        notifyNewAppointment(appointment);
        return appointmentMapper.toResponse(appointment);
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentResponse getByToken(UUID token) {
        Appointment appointment = appointmentRepository.findByCancelTokenWithDetails(token)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada"));
        return appointmentMapper.toResponse(appointment, true);
    }

    @Override
    @Transactional
    public AppointmentResponse cancelByToken(UUID token, CancelAppointmentRequest request) {
        Appointment appointment = appointmentRepository.findByCancelToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada"));
        return cancelInternal(appointment, request != null ? request.getReason() : null, null, true);
    }

    @Override
    @Transactional
    public AppointmentResponse cancelOwn(Long appointmentId, CancelAppointmentRequest request) {
        User currentUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        requireOwnAppointment(currentUser, appointment, "No puedes cancelar una cita que no es tuya");
        return cancelInternal(appointment, request != null ? request.getReason() : null, currentUser, true);
    }

    @Override
    @Transactional
    public AppointmentResponse cancelByStaff(Long appointmentId, CancelAppointmentRequest request) {
        User currentUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        requireDoctorOwnsAppointment(currentUser, appointment, "No puedes cancelar una cita de otro doctor");
        checkReceptionistSpecialtyAllowed(currentUser, appointment.getDoctor(), "cancelar citas");
        return cancelInternal(appointment, request != null ? request.getReason() : null, currentUser, false);
    }

    @Override
    @Transactional
    public AppointmentResponse releaseSlot(Long appointmentId) {
        User currentUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        requireDoctorOwnsAppointment(currentUser, appointment, "No puedes liberar el espacio de la cita de otro doctor");
        checkReceptionistSpecialtyAllowed(currentUser, appointment.getDoctor(), "liberar espacios de citas");
        if (appointment.getStatus() != AppointmentStatus.CANCELLED
                || Boolean.TRUE.equals(appointment.getSlotReleased())
                || !Boolean.TRUE.equals(appointment.getReleaseEligible())) {
            throw new BusinessException("Esta cita no es elegible para liberar el espacio");
        }
        appointment.setSlotReleased(true);
        appointment.setReleasedBy(currentUser);
        appointment.setReleasedAt(LocalDateTime.now());
        appointment.setUpdatedBy(currentUser);
        appointment = appointmentRepository.save(appointment);

        String roleLabel = switch (currentUser.getRole().getName()) {
            case DOCTOR -> "el doctor";
            case RECEPTIONIST -> "recepción";
            default -> "administración";
        };
        recordHistory(appointment, AppointmentStatus.CANCELLED, AppointmentStatus.CANCELLED, currentUser,
                "Espacio liberado manualmente por " + roleLabel + " ("
                        + currentUser.getFirstName() + " " + currentUser.getLastName() + ")");

        return appointmentMapper.toResponse(appointment);
    }

    @Override
    @Transactional
    public AppointmentResponse reschedule(Long appointmentId, RescheduleRequest request) {
        User actingUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        requireDoctorOwnsAppointment(actingUser, appointment, "No puedes reprogramar una cita de otro doctor");
        checkReceptionistSpecialtyAllowed(actingUser, appointment.getDoctor(), "reprogramar citas");
        return applyReschedule(appointment, request, actingUser, "Cita reprogramada", true);
    }

    @Override
    @Transactional
    public AppointmentResponse rescheduleOwn(Long appointmentId, RescheduleRequest request) {
        User currentUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        requireOwnAppointment(currentUser, appointment, "No puedes reprogramar una cita que no es tuya");
        requireSelfRescheduleWindow(appointment);
        return applyReschedule(appointment, request, currentUser, "Cita reprogramada por el paciente", false);
    }

    @Override
    @Transactional
    public AppointmentResponse rescheduleByToken(UUID token, RescheduleRequest request) {
        Appointment appointment = appointmentRepository.findByCancelTokenWithDetails(token)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada"));
        requireSelfRescheduleWindow(appointment);
        AppointmentResponse response = applyReschedule(appointment, request, null, "Cita reprogramada por el paciente (invitado)", false);
        response.setCancelToken(appointment.getCancelToken().toString());
        return response;
    }

    private void requireSelfRescheduleWindow(Appointment appointment) {
        LocalDateTime start = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime());
        if (LocalDateTime.now().isAfter(start.minusHours(selfRescheduleMinHours))) {
            throw new BusinessException("Solo puedes reprogramar tu cita con al menos " + selfRescheduleMinHours
                    + " horas de anticipación. Comunícate con el hospital para hacer el cambio.");
        }
    }

    /** Reprogramación compartida por recepción/doctor ({@code staffMode}=true, sin los 30 min
     * de anticipación) y por el propio paciente. El espacio anterior queda libre de inmediato
     * (la cita se mueve, no se cancela). */
    private AppointmentResponse applyReschedule(Appointment appointment, RescheduleRequest request, User actingUser,
                                                String historyNote, boolean staffMode) {
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new BusinessException("Solo se pueden reprogramar citas agendadas");
        }
        Branch branch = request.getBranchId() != null ? findActiveBranch(request.getBranchId()) : appointment.getBranch();
        Doctor doctor = appointment.getDoctor();
        validateDoctorAssignedToBranch(doctor, branch);
        LocalTime endTime = availabilityService.resolveEndTimeOrThrow(
                doctor, branch, request.getAppointmentDate(), request.getStartTime(), staffMode);

        LocalDate oldDate = appointment.getAppointmentDate();
        LocalTime oldStartTime = appointment.getStartTime();

        Long currentAppointmentId = appointment.getId();
        List<Appointment> occupants = appointmentRepository
                .lockOccupantsForSlot(doctor.getId(), request.getAppointmentDate(), request.getStartTime(), endTime).stream()
                .filter(a -> !a.getId().equals(currentAppointmentId))
                .toList();
        if (!occupants.isEmpty()) {
            throw new SlotUnavailableException("El horario seleccionado ya no está disponible");
        }
        requireNoPatientOverlap(appointment.getPatient(), request.getAppointmentDate(), request.getStartTime(), endTime,
                currentAppointmentId);

        appointment.setBranch(branch);
        appointment.setAppointmentDate(request.getAppointmentDate());
        appointment.setStartTime(request.getStartTime());
        appointment.setEndTime(endTime);
        // Ya no es un sobrecupo (el horario nuevo está libre), y la llegada registrada era
        // para la fecha anterior.
        appointment.setOverbooked(false);
        appointment.setArrivedAt(null);
        // El recordatorio de 2 horas es de la fecha NUEVA: si ya se mandó para la anterior, hay
        // que volver a armarlo -- salvo que la nueva ya esté a menos de 2 horas (el aviso de
        // reprogramación que acaba de salir ya cumple esa función).
        appointment.setReminder2hSentAt(startsWithinTwoHours(request.getAppointmentDate(), request.getStartTime())
                ? LocalDateTime.now() : null);
        appointment.setUpdatedBy(actingUser);
        try {
            appointment = appointmentRepository.save(appointment);
        } catch (DataIntegrityViolationException e) {
            throw new SlotUnavailableException("El horario seleccionado ya no está disponible");
        }

        recordHistory(appointment, AppointmentStatus.SCHEDULED, AppointmentStatus.SCHEDULED, actingUser, historyNote);

        notificationService.notifyAppointmentRescheduled(appointment, oldDate, oldStartTime);
        Appointment saved = appointment;
        afterCommit(() -> {
            byte[] receiptPdf = tryBuildReceipt(saved);
            emailService.sendRescheduleNoticeToPatient(saved, oldDate, oldStartTime, receiptPdf);
            emailService.sendRescheduleNoticeToDoctor(saved, oldDate, oldStartTime, receiptPdf);
            emailService.sendRescheduleNoticeToReceptionists(saved, oldDate, oldStartTime, receiptPdf);
            emailService.sendRescheduleNoticeToAdmins(saved, oldDate, oldStartTime, receiptPdf);
        });

        return appointmentMapper.toResponse(appointment);
    }

    @Override
    @Transactional
    public AppointmentResponse markArrived(Long appointmentId) {
        User currentUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        requireDoctorOwnsAppointment(currentUser, appointment, "No puedes registrar la llegada de una cita de otro doctor");
        checkReceptionistSpecialtyAllowed(currentUser, appointment.getDoctor(), "registrar la llegada de pacientes");
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new BusinessException("Solo se puede registrar la llegada de una cita programada");
        }
        if (!appointment.getAppointmentDate().equals(LocalDate.now())) {
            throw new BusinessException("La llegada solo se puede registrar el día de la cita");
        }
        if (appointment.getArrivedAt() != null) {
            return appointmentMapper.toResponse(appointment);
        }
        appointment.setArrivedAt(LocalDateTime.now());
        appointment.setUpdatedBy(currentUser);
        appointment = appointmentRepository.save(appointment);
        recordHistory(appointment, AppointmentStatus.SCHEDULED, AppointmentStatus.SCHEDULED, currentUser,
                "Llegada del paciente registrada por " + currentUser.getFirstName() + " " + currentUser.getLastName());
        return appointmentMapper.toResponse(appointment);
    }

    @Override
    @Transactional
    public AppointmentResponse markCompleted(Long appointmentId) {
        User currentUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        requireDoctorOwnsAppointment(currentUser, appointment, "No puedes marcar como atendida una cita de otro doctor");
        checkReceptionistSpecialtyAllowed(currentUser, appointment.getDoctor(), "marcar citas como atendidas");

        AppointmentStatus previous = appointment.getStatus();
        String note;
        if (previous == AppointmentStatus.NO_SHOW) {
            // Corrección: el job automático (o un error humano) la marcó "no asistió" pero el
            // paciente sí fue atendido -- antes esto solo se podía arreglar emitiendo una receta.
            note = "Corregida a atendida (estaba como no asistió) por " + currentUser.getFirstName() + " " + currentUser.getLastName();
        } else if (previous == AppointmentStatus.SCHEDULED) {
            // Antes de la hora solo se permite si el paciente ya llegó (se le atendió antes).
            if (appointment.getArrivedAt() == null && LocalDateTime.now().isBefore(startOf(appointment))) {
                throw new BusinessException("No puedes marcar el estado de una cita que todavía no ha ocurrido "
                        + "(si el paciente ya está aquí, registra primero su llegada)");
            }
            note = "Marcada como atendida por " + currentUser.getFirstName() + " " + currentUser.getLastName();
        } else {
            throw new BusinessException("Solo se puede marcar como atendida una cita programada o marcada como no asistió");
        }
        appointment.setStatus(AppointmentStatus.COMPLETED);
        appointment.setUpdatedBy(currentUser);
        appointment = appointmentRepository.save(appointment);
        recordHistory(appointment, previous, AppointmentStatus.COMPLETED, currentUser, note);
        return appointmentMapper.toResponse(appointment);
    }

    @Override
    @Transactional
    public AppointmentResponse markNoShow(Long appointmentId) {
        User currentUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        requireDoctorOwnsAppointment(currentUser, appointment, "No puedes marcar como no asistió una cita de otro doctor");
        checkReceptionistSpecialtyAllowed(currentUser, appointment.getDoctor(), "marcar inasistencias de citas");
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new BusinessException("Solo se puede cambiar el estado de una cita programada");
        }
        if (LocalDateTime.now().isBefore(startOf(appointment))) {
            throw new BusinessException("No puedes marcar el estado de una cita que todavía no ha ocurrido");
        }
        if (appointment.getArrivedAt() != null) {
            throw new BusinessException("El paciente ya registró su llegada; no se puede marcar como no asistió");
        }
        appointment.setStatus(AppointmentStatus.NO_SHOW);
        appointment.setUpdatedBy(currentUser);
        appointment = appointmentRepository.save(appointment);
        recordHistory(appointment, AppointmentStatus.SCHEDULED, AppointmentStatus.NO_SHOW, currentUser,
                "Marcada como no asistió por " + currentUser.getFirstName() + " " + currentUser.getLastName());
        return appointmentMapper.toResponse(appointment);
    }

    /** Corre cada minuto: si nadie marcó "atendida"/"no asistió" y ya pasó el fin del horario
     * MÁS el margen de gracia (app.appointments.no-show-grace-minutes -- una consulta puede
     * alargarse un poco, o el paciente llegar justo al final), se asume que no se presentó.
     * Nunca toca citas con la llegada del paciente registrada. {@code changedBy} queda nulo en
     * el historial -- aquí no hay un usuario autenticado detrás, es el sistema el que actúa. */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void autoMarkPastDueAsNoShow() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(noShowGraceMinutes);
        List<Appointment> pastDue = appointmentRepository.findPastDueScheduled(cutoff.toLocalDate(), cutoff.toLocalTime());
        for (Appointment appointment : pastDue) {
            appointment.setStatus(AppointmentStatus.NO_SHOW);
            appointmentRepository.save(appointment);
            recordHistory(appointment, AppointmentStatus.SCHEDULED, AppointmentStatus.NO_SHOW, null,
                    "Marcada automáticamente como no asistió: nadie la marcó antes de que terminara su horario");
        }
        // El paciente sí llegó (llegada registrada) pero nadie cerró la cita a tiempo.
        for (Appointment appointment : appointmentRepository.findStaleArrived(LocalDate.now())) {
            appointment.setStatus(AppointmentStatus.COMPLETED);
            appointmentRepository.save(appointment);
            recordHistory(appointment, AppointmentStatus.SCHEDULED, AppointmentStatus.COMPLETED, null,
                    "Cerrada automáticamente como atendida: el paciente registró su llegada y nadie la cerró ese día");
        }
    }

    /** Corre cada 5 minutos: manda un recordatorio por correo a cada cita programada que
     * empieza dentro de las próximas 2 horas y todavía no lo tiene. {@code reminder2hSentAt}
     * se marca en la misma transacción y los correos salen hasta el commit: si dos instancias
     * corren el job a la vez, gracias a {@code @Version} solo una confirma y solo esa manda. */
    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void sendTwoHourReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.plusHours(2);
        List<Appointment> due = appointmentRepository.findDueForTwoHourReminder(
                now.toLocalDate(), now.toLocalTime(), threshold.toLocalDate(), threshold.toLocalTime());
        for (Appointment appointment : due) {
            appointment.setReminder2hSentAt(LocalDateTime.now());
            appointmentRepository.save(appointment);
        }
        if (!due.isEmpty()) {
            afterCommit(() -> due.forEach(emailService::sendAppointmentReminder));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponse> listOwn() {
        User currentUser = requireCurrentUser();
        Patient patient = patientRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe un paciente ligado a esta cuenta"));
        return appointmentRepository.findByPatient_IdOrderByAppointmentDateDescStartTimeDesc(patient.getId()).stream()
                .map(appointmentMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AppointmentResponse> searchOwn(Long doctorId, Long specialtyId, LocalDate from, LocalDate to,
                                                 String status, Pageable pageable) {
        User currentUser = requireCurrentUser();
        Patient patient = patientRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe un paciente ligado a esta cuenta"));
        AppointmentStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            statusEnum = AppointmentStatus.valueOf(status.toUpperCase());
        }
        Specification<Appointment> spec =
                AppointmentSpecifications.search(doctorId, patient.getId(), null, specialtyId, from, to, statusEnum, null);
        // Sin un estado explícito, esta búsqueda alimenta la sección "Historial" de Mis citas,
        // que va debajo de "Próximas" (esa sí pide status=SCHEDULED de forma explícita) -- si no
        // excluyéramos SCHEDULED aquí, la misma cita programada aparecería duplicada en ambas.
        if (statusEnum == null) {
            spec = spec.and((root, query, cb) -> cb.notEqual(root.get("status"), AppointmentStatus.SCHEDULED));
        }
        return appointmentRepository
                .findAll(spec, pageable)
                .map(appointmentMapper::toResponse);
    }

    /** Antes devolvía las citas del invitado al navegador (con su token de cancelación y el
     * motivo de consulta) a cualquiera que supiera teléfono + nombre. Ahora solo manda los
     * enlaces al correo que el paciente dejó al agendar, y responde exactamente igual haya o
     * no citas -- no confirma a un tercero que ese paciente existe. */
    @Override
    @Transactional(readOnly = true)
    public AppointmentResponse requestGuestAppointmentLinks(String phone, String firstName, String lastName,
                                                             LocalDate appointmentDate, LocalTime startTime) {
        if (phone == null || phone.isBlank() || firstName == null || firstName.isBlank()
                || lastName == null || lastName.isBlank()) {
            throw new BusinessException("Ingresa tu teléfono, nombre y apellido");
        }
        LocalDateTime now = LocalDateTime.now();
        List<Appointment> upcoming = appointmentRepository.findUpcomingGuestAppointments(
                phone.trim(), firstName.trim(), lastName.trim(), now.toLocalDate(), now.toLocalTime());

        // Con fecha y hora exactas (dato que solo conoce quien agendó, además de teléfono y nombre)
        // se entra a la cita directo -- es la vía para quien no dejó correo. No se devuelve el
        // motivo de consulta ni datos médicos del paciente, solo lo necesario para cancelar o
        // reprogramar; el límite por IP del endpoint frena los intentos de adivinar.
        if (appointmentDate != null && startTime != null) {
            Appointment match = upcoming.stream()
                    .filter(a -> a.getAppointmentDate().equals(appointmentDate) && a.getStartTime().equals(startTime))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No encontramos una cita con esos datos. Verifica teléfono, nombre, fecha y hora."));
            AppointmentResponse response = appointmentMapper.toResponse(match, true);
            response.setReasonForVisit(null);
            response.setPatient(null);
            return response;
        }

        Map<String, List<Appointment>> byEmail = new LinkedHashMap<>();
        for (Appointment appointment : upcoming) {
            String email = appointment.getPatient().getEmail();
            if (email != null && !email.isBlank()) {
                byEmail.computeIfAbsent(email.trim().toLowerCase(), k -> new java.util.ArrayList<>()).add(appointment);
            }
        }
        byEmail.forEach((email, appointments) -> emailService.sendGuestAppointmentLinks(
                email, appointments.get(0).getPatient().getFirstName(), appointments, frontendUrl));
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AppointmentResponse> search(Long doctorId, Long patientId, Long branchId, LocalDate from, LocalDate to,
                                             String status, String patientQuery, boolean pendingRelease,
                                             boolean pendingPayment, Pageable pageable) {
        User currentUser = requireCurrentUser();
        AppointmentStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            statusEnum = AppointmentStatus.valueOf(status.toUpperCase());
        }
        Specification<Appointment> spec =
                AppointmentSpecifications.search(doctorId, patientId, branchId, from, to, statusEnum, patientQuery);
        if (pendingRelease) {
            spec = spec.and(AppointmentSpecifications.pendingRelease(LocalDate.now()));
        }
        if (pendingPayment) {
            spec = spec.and(AppointmentSpecifications.pendingPayment());
        }
        List<Long> restriction = receptionistSpecialtyRestriction(currentUser);
        if (!restriction.isEmpty()) {
            spec = spec.and(AppointmentSpecifications.doctorSpecialtyIn(restriction));
        }
        // Un doctor (historial en la ficha de Pacientes) solo ve sus propias citas.
        Long ownDoctorId = doctorScope.doctorIdOf(currentUser).orElse(null);
        if (ownDoctorId != null) {
            spec = spec.and(AppointmentSpecifications.search(ownDoctorId, null, null, null, null, null, null));
        }
        return appointmentRepository.findAll(spec, pageable).map(appointmentMapper::toResponse);
    }

    private static final java.util.regex.Pattern UUID_IN_TEXT = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponse> lookupForCharge(String q) {
        User actor = requireCurrentUser();
        // Un doctor solo cobra sus propias citas (recepción/admin: null = todas).
        Long ownDoctorId = doctorScope.doctorIdOf(actor).orElse(null);
        String query = q == null ? "" : q.trim();
        List<Appointment> found;
        var uuid = UUID_IN_TEXT.matcher(query);
        if (uuid.find()) {
            // El QR del comprobante trae la URL con el código de la cita (o se pega solo el código).
            found = appointmentRepository.findByCancelTokenWithDetails(UUID.fromString(uuid.group()))
                    .map(List::of).orElse(List.of());
        } else if (query.matches("#?\\d{1,9}")) {
            // Folio de la cita (los teléfonos tienen 10 dígitos y se buscan como texto).
            found = appointmentRepository.findByIdWithDetails(Long.parseLong(query.replace("#", "")))
                    .map(List::of).orElse(List.of());
        } else {
            LocalDate today = LocalDate.now();
            Specification<Appointment> spec = query.isEmpty()
                    ? AppointmentSpecifications.chargeQueue(today)
                    : AppointmentSpecifications.search(null, null, null, today.minusDays(30), today.plusDays(1), null, query)
                            .and(AppointmentSpecifications.statusIn(AppointmentStatus.SCHEDULED, AppointmentStatus.COMPLETED));
            if (ownDoctorId != null) {
                spec = spec.and(AppointmentSpecifications.search(ownDoctorId, null, null, null, null, null, null));
            }
            found = appointmentRepository.findAll(spec, org.springframework.data.domain.PageRequest.of(0, 30,
                    org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "appointmentDate", "startTime"))).getContent();
        }
        List<Long> restriction = receptionistSpecialtyRestriction(actor);
        return found.stream()
                .filter(a -> restriction.isEmpty() || restriction.contains(a.getDoctor().getSpecialty().getId()))
                // QR/folio buscan una cita puntual: a un doctor solo se le muestra si es suya.
                .filter(a -> ownDoctorId == null || ownDoctorId.equals(a.getDoctor().getId()))
                .map(appointmentMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponse> listForOwnDoctor(LocalDate from, LocalDate to) {
        User currentUser = requireCurrentUser();
        Doctor doctor = doctorRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe un perfil de doctor para este usuario"));
        return appointmentRepository
                .findByDoctor_IdAndAppointmentDateBetweenOrderByAppointmentDateAscStartTimeAsc(doctor.getId(), from, to)
                .stream()
                .map(appointmentMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AppointmentResponse> searchOwnDoctor(Long branchId, LocalDate from, LocalDate to, String status,
                                                       String patientQuery, Pageable pageable) {
        User currentUser = requireCurrentUser();
        Doctor doctor = doctorRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe un perfil de doctor para este usuario"));
        AppointmentStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            statusEnum = AppointmentStatus.valueOf(status.toUpperCase());
        }
        return appointmentRepository
                .findAll(AppointmentSpecifications.search(doctor.getId(), null, branchId, from, to, statusEnum, patientQuery), pageable)
                .map(appointmentMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateReceiptPdfOwn(Long appointmentId) {
        User currentUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        requireOwnAppointment(currentUser, appointment, "No puedes ver el comprobante de una cita que no es tuya");
        return appointmentReceiptService.build(appointment);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateReceiptPdfByToken(UUID token) {
        Appointment appointment = appointmentRepository.findByCancelTokenWithDetails(token)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada"));
        return appointmentReceiptService.build(appointment);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateReceiptPdfForStaff(Long appointmentId) {
        User currentUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        checkReceptionistSpecialtyAllowed(currentUser, appointment.getDoctor(), "ver el comprobante de citas");
        return appointmentReceiptService.build(appointment);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateReceiptPdfForDoctor(Long appointmentId) {
        User currentUser = requireCurrentUser();
        Appointment appointment = findAppointment(appointmentId);
        requireDoctorOwnsAppointment(currentUser, appointment, "No puedes ver el comprobante de la cita de otro doctor");
        return appointmentReceiptService.build(appointment);
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * @param overbook           recepción/admin pidió empalmar la cita sobre un horario ocupado
     * @param enforcePatientLimits tope de citas programadas por paciente (recepción no lo
     *                           tiene: agenda a nombre de quien esté en el mostrador)
     * @param enforcePhoneLimit  agendado de invitado: además del tope por paciente, hay un tope
     *                           por teléfono (alguien podría variar el nombre para apartar
     *                           muchos horarios)
     * Si recepción agenda un horario que ya empezó (paciente que llega sin cita), se registra
     * su llegada de una vez para que el job automático no la marque "no asistió".
     */
    private Appointment createAndPersist(Doctor doctor, Branch branch, Patient patient, LocalDate date,
                                          LocalTime startTime, LocalTime endTime, String reasonForVisit,
                                          User createdByUser, boolean overbook, boolean enforcePatientLimits,
                                          boolean enforcePhoneLimit) {
        List<Appointment> occupants = appointmentRepository.lockOccupantsForSlot(doctor.getId(), date, startTime, endTime);
        boolean liveOccupant = occupants.stream().anyMatch(a -> a.getStatus() != AppointmentStatus.CANCELLED);
        // Con sobrecupo (solo recepción/admin) se permite empalmar sobre una cita viva; un
        // ocupante que solo es una cancelación sin liberar sigue bloqueando el espacio (hay que
        // liberarlo primero, es la regla de negocio de esas cancelaciones).
        if (!occupants.isEmpty() && !(overbook && liveOccupant)) {
            throw new SlotUnavailableException("El horario seleccionado ya no está disponible");
        }
        boolean overbooked = overbook && liveOccupant;

        requireNoPatientOverlap(patient, date, startTime, endTime, -1L);
        if (enforcePatientLimits) {
            enforceUpcomingLimits(patient, enforcePhoneLimit);
        }

        LocalDateTime now = LocalDateTime.now();
        boolean alreadyStarted = createdByUser != null && !LocalDateTime.of(date, startTime).isAfter(now);
        Appointment appointment = Appointment.builder()
                .doctor(doctor)
                .branch(branch)
                .patient(patient)
                .appointmentDate(date)
                .startTime(startTime)
                .endTime(endTime)
                .status(AppointmentStatus.SCHEDULED)
                .reasonForVisit(reasonForVisit)
                .slotReleased(false)
                .createdByUser(createdByUser)
                .cancelToken(UUID.randomUUID())
                .overbooked(overbooked)
                // Foto del precio del doctor en este momento (nulo = sin precio definido).
                .price(doctor.getConsultationPrice())
                // Paciente que llega sin cita a recepción: ya está aquí.
                .arrivedAt(alreadyStarted ? now : null)
                // Foto de alergias/tipo de sangre del paciente TAL COMO ESTÁN en este momento --
                // ver Appointment#allergiesSnapshot.
                .allergiesSnapshot(patient.getAllergies())
                .bloodTypeSnapshot(patient.getBloodType())
                .bloodTypeOtherSnapshot(patient.getBloodTypeOther())
                // Si la cita ya empieza en menos de 2 horas, la confirmación que acaba de salir
                // cumple la función del recordatorio -- no mandar dos correos casi juntos.
                .reminder2hSentAt(startsWithinTwoHours(date, startTime) ? now : null)
                .build();
        appointment.setCreatedBy(SecurityUtils.getCurrentUserOrNull());

        try {
            appointment = appointmentRepository.save(appointment);
        } catch (DataIntegrityViolationException e) {
            // Segunda linea de defensa (indice unico parcial / restricción de exclusión):
            // dos requests concurrentes pasaron el chequeo del lock casi al mismo tiempo
            // porque, al ser la primera reserva de este slot, no habia fila previa que bloquear.
            throw new SlotUnavailableException("El horario seleccionado ya no está disponible");
        }

        recordHistory(appointment, null, AppointmentStatus.SCHEDULED, SecurityUtils.getCurrentUserOrNull(),
                overbooked ? "Cita agendada como sobrecupo" : "Cita agendada");

        return appointment;
    }

    /** La misma persona no puede tener dos citas programadas que se traslapen, aunque sean
     * con doctores distintos. */
    private void requireNoPatientOverlap(Patient patient, LocalDate date, LocalTime start, LocalTime end, Long excludeId) {
        if (appointmentRepository.countPatientOverlaps(patient.getId(), date, start, end, excludeId) > 0) {
            throw new BusinessException("Ya tienes otra cita agendada en ese horario");
        }
    }

    private void enforceUpcomingLimits(Patient patient, boolean alsoByPhone) {
        LocalDateTime now = LocalDateTime.now();
        if (appointmentRepository.countUpcomingByPatient(patient.getId(), now.toLocalDate(), now.toLocalTime()) >= maxActivePerPatient) {
            throw new BusinessException("Ya tienes " + maxActivePerPatient + " citas programadas, que es el máximo. "
                    + "Cancela alguna o espera a que se atienda para agendar otra.");
        }
        if (alsoByPhone && patient.getPhone() != null && !patient.getPhone().isBlank()
                && appointmentRepository.countUpcomingByPhone(patient.getPhone(), now.toLocalDate(), now.toLocalTime()) >= maxActivePerPhone) {
            throw new BusinessException("Este teléfono ya tiene el máximo de citas programadas. "
                    + "Comunícate con el hospital si necesitas agendar más.");
        }
    }

    private boolean startsWithinTwoHours(LocalDate date, LocalTime start) {
        return !LocalDateTime.of(date, start).isAfter(LocalDateTime.now().plusHours(2));
    }

    /** {@code patientInitiated}: paciente (con cuenta o por token de invitado) -- una cita que
     * ya empezó no se puede cancelar por su cuenta; recepción/doctor sí pueden (ej. corregir). */
    private AppointmentResponse cancelInternal(Appointment appointment, String reason, User actingUserOrNull,
                                                boolean patientInitiated) {
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new BusinessException("Solo se pueden cancelar citas agendadas");
        }
        LocalDateTime appointmentStart = startOf(appointment);
        if (patientInitiated && !LocalDateTime.now().isBefore(appointmentStart)) {
            throw new BusinessException("La cita ya inició; comunícate con el hospital para cualquier cambio");
        }
        boolean releaseEligible = !LocalDateTime.now().isAfter(appointmentStart.minusHours(24));

        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancelledBy(actingUserOrNull);
        appointment.setCancelledAt(LocalDateTime.now());
        appointment.setCancelReason(reason);
        appointment.setReleaseEligible(releaseEligible);
        appointment.setSlotReleased(false);
        appointment.setUpdatedBy(actingUserOrNull);
        appointment = appointmentRepository.save(appointment);

        recordHistory(appointment, AppointmentStatus.SCHEDULED, AppointmentStatus.CANCELLED, actingUserOrNull, reason);
        // Pago anticipado: reembolsa (>= 1 h de anticipación) o deja por revisar por el administrador.
        paymentService.handleCancellation(appointment);

        notificationService.notifyAppointmentCancelled(appointment);
        Appointment saved = appointment;
        afterCommit(() -> {
            byte[] receiptPdf = tryBuildReceipt(saved);
            emailService.sendCancellationNoticeToPatient(saved, receiptPdf);
            emailService.sendCancellationNoticeToDoctor(saved, receiptPdf);
            emailService.sendCancellationNoticeToReceptionists(saved, receiptPdf);
            emailService.sendCancellationNoticeToAdmins(saved, receiptPdf);
        });

        return appointmentMapper.toResponse(appointment);
    }

    private void notifyNewAppointment(Appointment appointment) {
        notificationService.notifyNewAppointment(appointment);
        afterCommit(() -> {
            byte[] receiptPdf = tryBuildReceipt(appointment);
            emailService.sendAppointmentConfirmationToPatient(appointment, receiptPdf);
            emailService.sendNewAppointmentNoticeToDoctor(appointment, receiptPdf);
            emailService.sendNewAppointmentNoticeToReceptionists(appointment, receiptPdf);
            emailService.sendNewAppointmentNoticeToAdmins(appointment, receiptPdf);
        });
    }

    /** Ejecuta {@code action} hasta que la transacción actual haya confirmado (commit): un
     * correo sobre una cita que finalmente no se guardó (conflicto de versión, violación de
     * índice al hacer commit) sería peor que no mandarlo. Además genera el PDF y manda los
     * correos ya sin mantener abiertos los bloqueos de la base de datos. Nunca deja que una
     * falla aquí revierta o tumbe la operación que ya se guardó. */
    private void afterCommit(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (Exception e) {
                log.warn("Falló una tarea posterior al guardado de la cita", e);
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

    private void recordHistory(Appointment appointment, AppointmentStatus from, AppointmentStatus to, User changedBy, String note) {
        appointmentStatusHistoryRepository.save(AppointmentStatusHistory.builder()
                .appointment(appointment)
                .fromStatus(from)
                .toStatus(to)
                .changedBy(changedBy)
                .note(note)
                .build());
    }

    private LocalDateTime startOf(Appointment appointment) {
        return LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime());
    }

    /** El comprobante adjunto es un plus para el correo, nunca un requisito -- si algo falla
     * al generarlo (ej. un logo corrupto que ni el fallback interno pudo resolver), los
     * correos se mandan igual sin adjunto en vez de tumbar el agendado/cancelación. */
    private byte[] tryBuildReceipt(Appointment appointment) {
        try {
            return appointmentReceiptService.build(appointment);
        } catch (Exception e) {
            log.warn("No se pudo generar el comprobante para adjuntar al correo de la cita {}", appointment.getId(), e);
            return null;
        }
    }

    private Doctor findActiveDoctor(Long id) {
        return doctorRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor no encontrado: " + id));
    }

    private Branch findActiveBranch(Long id) {
        return branchRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sede no encontrada: " + id));
    }

    private void validateDoctorAssignedToBranch(Doctor doctor, Branch branch) {
        boolean assigned = doctor.getBranches().stream().anyMatch(b -> b.getId().equals(branch.getId()));
        if (!assigned) {
            throw new BusinessException("El doctor no atiende en la sede seleccionada");
        }
    }

    private Appointment findAppointment(Long id) {
        return appointmentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada: " + id));
    }

    private User requireCurrentUser() {
        User user = SecurityUtils.getCurrentUserOrNull();
        if (user == null) {
            throw new ResourceNotFoundException("No hay sesión activa");
        }
        return user;
    }

    /** Si quien actúa es un DOCTOR, la cita debe ser suya (recepción/admin: cualquiera). */
    private void requireDoctorOwnsAppointment(User user, Appointment appointment, String message) {
        if (user.getRole().getName() == RoleName.DOCTOR) {
            Doctor doctor = doctorRepository.findByUser_Id(user.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("No existe un perfil de doctor para este usuario"));
            if (!appointment.getDoctor().getId().equals(doctor.getId())) {
                throw new BusinessException(message);
            }
        }
    }

    private void requireOwnAppointment(User user, Appointment appointment, String message) {
        Patient ownPatient = patientRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe un paciente ligado a esta cuenta"));
        if (!appointment.getPatient().getId().equals(ownPatient.getId())) {
            throw new BusinessException(message);
        }
    }

    // ── Restricción de recepcionista por especialidad ───────────────
    // Una recepcionista SIN especialidades asignadas es general (sin restricción, mismo
    // comportamiento de siempre). Con una o más asignadas, queda acotada a doctores de esas
    // especialidades en TODO lo relacionado a citas: agendar, cancelar, reprogramar, liberar
    // espacio, ver comprobante y el listado/búsqueda de Citas (incluye el Dashboard de
    // recepción, que reusa el mismo método de búsqueda). Nunca aplica a ADMIN.

    private List<Long> receptionistSpecialtyRestriction(User user) {
        if (user.getRole().getName() != RoleName.RECEPTIONIST || user.getSpecialties().isEmpty()) {
            return List.of();
        }
        return user.getSpecialties().stream().map(Specialty::getId).toList();
    }

    private void checkReceptionistSpecialtyAllowed(User actingUser, Doctor doctor, String action) {
        List<Long> restriction = receptionistSpecialtyRestriction(actingUser);
        if (!restriction.isEmpty() && !restriction.contains(doctor.getSpecialty().getId())) {
            throw new BusinessException("No puedes " + action + " de la especialidad " + doctor.getSpecialty().getName());
        }
    }
}
