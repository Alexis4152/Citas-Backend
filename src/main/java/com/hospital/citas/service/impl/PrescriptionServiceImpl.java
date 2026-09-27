package com.hospital.citas.service.impl;

import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.PrescriptionRequest;
import com.hospital.citas.dto.response.PrescriptionResponse;
import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.AppointmentStatusHistory;
import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.Prescription;
import com.hospital.citas.entity.Specialty;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.PrescriptionMapper;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.AppointmentStatusHistoryRepository;
import com.hospital.citas.repository.DoctorRepository;
import com.hospital.citas.repository.PrescriptionRepository;
import com.hospital.citas.security.DoctorScope;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.EmailService;
import com.hospital.citas.service.PrescriptionReceiptService;
import com.hospital.citas.service.PrescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PrescriptionServiceImpl implements PrescriptionService {

    private final PrescriptionRepository prescriptionRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentStatusHistoryRepository appointmentStatusHistoryRepository;
    private final DoctorRepository doctorRepository;
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionReceiptService prescriptionReceiptService;
    private final EmailService emailService;
    private final DoctorScope doctorScope;

    @Override
    @Transactional
    public PrescriptionResponse create(PrescriptionRequest request) {
        User currentUser = requireCurrentUser();
        Doctor doctor = ownDoctorOf(currentUser);
        Appointment appointment = appointmentRepository.findByIdWithDetails(request.getAppointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada: " + request.getAppointmentId()));
        if (!appointment.getDoctor().getId().equals(doctor.getId())) {
            throw new BusinessException("No puedes generar una receta para la cita de otro doctor");
        }
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new BusinessException("No puedes generar una receta para una cita cancelada");
        }
        // No tiene sentido "atender" una consulta que todavía no empieza -- a diferencia de la
        // corrección de estado en markCompletedFromPrescription (que sí ignora el estado
        // previo), esta validación de horario no se salta nunca.
        LocalDateTime appointmentStart = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime());
        // Salvo que el paciente ya haya llegado (se le atendió antes de la hora agendada).
        if (appointment.getArrivedAt() == null && LocalDateTime.now().isBefore(appointmentStart)) {
            throw new BusinessException("Aún no puedes emitir la receta: podrás hacerlo a partir de las "
                    + appointment.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm")) + ", cuando empiece la consulta");
        }

        boolean hasActivePrescription = prescriptionRepository.findByAppointment_IdWithDetails(appointment.getId()).stream()
                .anyMatch(p -> !p.isVoided());
        if (hasActivePrescription && !Boolean.TRUE.equals(request.getConfirmAdditional())) {
            throw new BusinessException("Esta cita ya tiene una receta vigente. Anúlala si tiene un error, "
                    + "o confirma que quieres emitir una receta adicional.");
        }

        Prescription prescription = Prescription.builder()
                .appointment(appointment)
                .diagnosis(request.getDiagnosis() == null || request.getDiagnosis().isBlank() ? null : request.getDiagnosis().trim())
                .content(request.getContent())
                .build();
        prescription.setCreatedBy(currentUser);
        prescription = prescriptionRepository.save(prescription);
        markCompletedFromPrescription(appointment, currentUser);
        // Se relee con los JOIN FETCH del repositorio (doctor/paciente) para que el mapper no
        // dispare lazy-loads fuera de esta transacción.
        return prescriptionMapper.toResponse(findWithDetails(prescription.getId()));
    }

    /**
     * Emitir una receta es evidencia directa de que el paciente sí fue atendido -- más fuerte
     * que cualquier estado previo, incluso si el job automático ya la había marcado "no
     * asistió" por no haberse marcado a tiempo, o si el horario todavía no "empezaba" según el
     * reloj (el doctor pudo haber atendido antes de la hora agendada). Por eso corrige el
     * estado a COMPLETED sin pasar por AppointmentServiceImpl.validateTransitionToTerminal
     * (que exige SCHEDULED + horario ya cumplido) -- ninguna de las dos cosas debe bloquear
     * esta corrección. No hace nada si ya estaba COMPLETED, para no duplicar el historial.
     */
    private void markCompletedFromPrescription(Appointment appointment, User doctorUser) {
        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            return;
        }
        AppointmentStatus previousStatus = appointment.getStatus();
        appointment.setStatus(AppointmentStatus.COMPLETED);
        appointment.setUpdatedBy(doctorUser);
        appointmentRepository.save(appointment);
        appointmentStatusHistoryRepository.save(AppointmentStatusHistory.builder()
                .appointment(appointment)
                .fromStatus(previousStatus)
                .toStatus(AppointmentStatus.COMPLETED)
                .changedBy(doctorUser)
                .note("Marcada como atendida automáticamente al generar una receta")
                .build());
    }

    @Override
    @Transactional
    public PrescriptionResponse voidPrescription(Long prescriptionId, String reason) {
        User currentUser = requireCurrentUser();
        Doctor doctor = ownDoctorOf(currentUser);
        Prescription prescription = findWithDetails(prescriptionId);
        if (!prescription.getAppointment().getDoctor().getId().equals(doctor.getId())) {
            throw new BusinessException("No puedes anular la receta de otro doctor");
        }
        if (prescription.isVoided()) {
            throw new BusinessException("Esta receta ya está anulada");
        }
        prescription.setVoidedAt(LocalDateTime.now());
        prescription.setVoidedBy(currentUser);
        prescription.setVoidReason(reason.trim());
        prescriptionRepository.save(prescription);
        return prescriptionMapper.toResponse(findWithDetails(prescriptionId));
    }

    @Override
    @Transactional(readOnly = true)
    public void sendByEmail(Long prescriptionId) {
        User currentUser = requireCurrentUser();
        Doctor doctor = ownDoctorOf(currentUser);
        Prescription prescription = findWithDetails(prescriptionId);
        if (!prescription.getAppointment().getDoctor().getId().equals(doctor.getId())) {
            throw new BusinessException("No puedes enviar por correo la receta de otro doctor");
        }
        if (prescription.isVoided()) {
            throw new BusinessException("Esta receta está anulada; no se puede enviar por correo");
        }
        String patientEmail = prescription.getAppointment().getPatient().getEmail();
        if (patientEmail == null || patientEmail.isBlank()) {
            throw new BusinessException("El paciente no tiene un correo registrado");
        }
        byte[] pdf = prescriptionReceiptService.build(prescription);
        emailService.sendPrescriptionToPatient(prescription, pdf);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generatePdfForDoctor(Long prescriptionId) {
        User currentUser = requireCurrentUser();
        Doctor doctor = ownDoctorOf(currentUser);
        Prescription prescription = findWithDetails(prescriptionId);
        if (!prescription.getAppointment().getDoctor().getId().equals(doctor.getId())) {
            throw new BusinessException("No puedes descargar la receta de otro doctor");
        }
        return prescriptionReceiptService.build(prescription);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generatePdfForStaff(Long prescriptionId) {
        User currentUser = requireCurrentUser();
        Prescription prescription = findWithDetails(prescriptionId);
        checkReceptionistSpecialtyAllowed(currentUser, prescription.getAppointment().getDoctor(), "descargar recetas");
        doctorScope.requireOwnDoctor(currentUser, prescription.getAppointment().getDoctor(), "descargar las recetas de");
        return prescriptionReceiptService.build(prescription);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionResponse> listForAppointmentAsDoctor(Long appointmentId) {
        User currentUser = requireCurrentUser();
        Doctor doctor = ownDoctorOf(currentUser);
        Appointment appointment = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada: " + appointmentId));
        if (!appointment.getDoctor().getId().equals(doctor.getId())) {
            throw new BusinessException("No puedes ver las recetas de la cita de otro doctor");
        }
        return prescriptionRepository.findByAppointment_IdWithDetails(appointmentId).stream()
                .map(prescriptionMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PrescriptionResponse> searchForOwnDoctor(String patientQuery, Pageable pageable) {
        User currentUser = requireCurrentUser();
        Doctor doctor = ownDoctorOf(currentUser);
        String normalized = patientQuery == null ? "" : patientQuery.trim().toLowerCase();
        Page<Prescription> page = prescriptionRepository.searchByDoctor(doctor.getId(), normalized, pageable);
        return PageResponse.of(page.map(prescriptionMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionResponse> listForPatientAsStaff(Long patientId) {
        User currentUser = requireCurrentUser();
        List<Long> restriction = receptionistSpecialtyRestriction(currentUser);
        Long ownDoctorId = doctorScope.doctorIdOf(currentUser).orElse(null);
        return prescriptionRepository.findByAppointment_Patient_IdWithDetails(patientId).stream()
                .filter(p -> restriction.isEmpty() || restriction.contains(p.getAppointment().getDoctor().getSpecialty().getId()))
                // Un doctor solo ve las recetas que él emitió (en sus propias citas).
                .filter(p -> ownDoctorId == null || ownDoctorId.equals(p.getAppointment().getDoctor().getId()))
                .map(prescriptionMapper::toResponse)
                .toList();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private Prescription findWithDetails(Long id) {
        return prescriptionRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Receta no encontrada: " + id));
    }

    private Doctor ownDoctorOf(User user) {
        return doctorRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe un perfil de doctor para este usuario"));
    }

    private User requireCurrentUser() {
        User user = SecurityUtils.getCurrentUserOrNull();
        if (user == null) {
            throw new ResourceNotFoundException("No hay sesión activa");
        }
        return user;
    }

    // Misma restricción de recepcionista por especialidad que AppointmentServiceImpl (ver ahí
    // el porqué) -- duplicada aquí en vez de compartida porque son 10 líneas y exponerla desde
    // AppointmentServiceImpl obligaría a romper su encapsulamiento (hoy es privada) solo para
    // este segundo uso.
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
