package com.hospital.citas.service.impl;

import com.hospital.citas.dto.request.MedicalInfoRequest;
import com.hospital.citas.dto.request.PatientRequest;
import com.hospital.citas.dto.response.PatientResponse;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.entity.Role;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.BloodType;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.DuplicateResourceException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.PatientMapper;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.PatientRepository;
import com.hospital.citas.repository.RoleRepository;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.EmailService;
import com.hospital.citas.service.PatientService;
import com.hospital.citas.util.TempPasswordGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PatientServiceImpl implements PatientService {

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AppointmentRepository appointmentRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<PatientResponse> search(String query, Long doctorId, Long specialtyId, Pageable pageable) {
        String q = query == null ? "" : query;
        return patientRepository.search(q, doctorId, specialtyId, pageable)
                .map(patientMapper::toResponse);
    }

    @Override
    @Transactional
    public PatientResponse create(PatientRequest request) {
        Patient patient = new Patient();
        patient.setFirstName(request.getFirstName());
        patient.setLastName(request.getLastName());
        patient.setPhone(request.getPhone());
        patient.setEmail(request.getEmail());
        patient.setCreatedBy(SecurityUtils.getCurrentUserOrNull());
        // Sin guardar todavía (setMedicalInfoFields no persiste) -- el único save() de este
        // método, más abajo, ya deja todo en una sola inserción.
        setMedicalInfoFields(patient, request.getMedicalInfo(), SecurityUtils.getCurrentUserOrNull());

        String temporaryPassword = null;
        if (Boolean.TRUE.equals(request.getCreateAccount())) {
            temporaryPassword = createAccountFor(patient, request);
        }

        patient = patientRepository.save(patient);

        PatientResponse response = patientMapper.toResponse(patient);
        response.setTemporaryPassword(temporaryPassword);
        return response;
    }

    /** Crea el User (rol PATIENT, contraseña temporal, cambio forzado en primer login --
     * patrón 02, igual que el alta de Doctor/Recepcionista) y lo liga al Patient que se está
     * dando de alta. Reusa el mismo modelo que el auto-registro público
     * (AuthServiceImpl.register + findOrCreatePatientForUser): un Patient con `user` no nulo
     * es, ni más ni menos, un paciente que puede iniciar sesión. */
    private String createAccountFor(Patient patient, PatientRequest request) {
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            throw new BusinessException("Se requiere un correo para crear la cuenta de acceso del paciente");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Ya existe una cuenta con ese correo");
        }
        Role patientRole = roleRepository.findByName(RoleName.PATIENT)
                .orElseThrow(() -> new IllegalStateException("Rol PATIENT no configurado"));

        String temporaryPassword = TempPasswordGenerator.generate();
        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(patientRole)
                .mustChangePassword(true)
                .build();
        user.setCreatedBy(SecurityUtils.getCurrentUserOrNull());
        user = userRepository.save(user);

        patient.setUser(user);

        emailService.sendTemporaryCredentials(user, temporaryPassword);
        return temporaryPassword;
    }

    @Override
    @Transactional
    public Patient findOrCreateGuestPatient(String firstName, String lastName, String phone, String email) {
        // Los pacientes de invitado/recepción nunca llevan `user`: nombre+teléfono es todo lo
        // que se exige (el correo es opcional). Si ya existe un invitado con EXACTAMENTE el mismo
        // teléfono + nombre + apellido se reutiliza (antes cada cita creaba un registro nuevo y
        // el historial de una misma persona quedaba regado en varios). Cualquier otro parecido
        // (mismo teléfono con otro nombre, mismo correo) NO se fusiona solo -- eso lo revisa
        // recepción con la herramienta de fusionar pacientes.
        String first = firstName.trim();
        String last = lastName.trim();
        String cleanEmail = email == null || email.isBlank() ? null : email.trim();
        var existing = patientRepository
                .findFirstByUserIsNullAndIsActiveTrueAndPhoneAndFirstNameIgnoreCaseAndLastNameIgnoreCaseOrderByIdAsc(phone, first, last);
        if (existing.isPresent()) {
            Patient patient = existing.get();
            if (cleanEmail != null && (patient.getEmail() == null || patient.getEmail().isBlank())) {
                patient.setEmail(cleanEmail);
                patient = patientRepository.save(patient);
            }
            return patient;
        }
        Patient patient = new Patient();
        patient.setFirstName(first);
        patient.setLastName(last);
        patient.setPhone(phone);
        patient.setEmail(cleanEmail);
        return patientRepository.save(patient);
    }

    @Override
    @Transactional
    public Patient findOrCreatePatientForUser(User user) {
        return patientRepository.findByUser_Id(user.getId())
                .orElseGet(() -> {
                    Patient patient = new Patient();
                    patient.setFirstName(user.getFirstName());
                    patient.setLastName(user.getLastName());
                    patient.setPhone(user.getPhone() != null ? user.getPhone() : "");
                    patient.setEmail(user.getEmail());
                    patient.setUser(user);
                    patient.setCreatedBy(user);
                    return patientRepository.save(patient);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Patient getEntityById(Long id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente no encontrado: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public PatientResponse getById(Long id) {
        return patientMapper.toResponse(getEntityById(id));
    }

    @Override
    @Transactional
    public void applyMedicalInfo(Patient patient, MedicalInfoRequest request, User modifiedBy) {
        setMedicalInfoFields(patient, request, modifiedBy);
        patientRepository.save(patient);
    }

    @Override
    @Transactional
    public PatientResponse updateMedicalInfo(Long patientId, MedicalInfoRequest request) {
        Patient patient = getEntityById(patientId);
        applyMedicalInfo(patient, request, SecurityUtils.getCurrentUserOrNull());
        return patientMapper.toResponse(patient);
    }

    @Override
    @Transactional
    public PatientResponse updateOwnMedicalInfo(MedicalInfoRequest request) {
        User currentUser = requireCurrentUser();
        Patient patient = findOwnPatient(currentUser);
        applyMedicalInfo(patient, request, currentUser);
        return patientMapper.toResponse(patient);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientResponse> findDuplicates(Long patientId) {
        Patient patient = getEntityById(patientId);
        return patientRepository.findPossibleDuplicates(
                        patient.getId(),
                        patient.getPhone() == null ? "" : patient.getPhone(),
                        patient.getEmail() == null ? "" : patient.getEmail())
                .stream().map(patientMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public PatientResponse merge(Long sourceId, Long targetId) {
        if (sourceId.equals(targetId)) {
            throw new BusinessException("Elige dos pacientes distintos para fusionar");
        }
        Patient source = getEntityById(sourceId);
        Patient target = getEntityById(targetId);
        if (!Boolean.TRUE.equals(source.getIsActive()) || !Boolean.TRUE.equals(target.getIsActive())) {
            throw new BusinessException("Alguno de los pacientes ya fue fusionado o dado de baja");
        }
        if (source.getUser() != null && target.getUser() != null) {
            throw new BusinessException("Los dos pacientes tienen cuenta de acceso; no se pueden fusionar automáticamente");
        }

        // La cuenta de acceso (si solo el origen la tiene) pasa al destino: se libera primero
        // del origen para no dejar dos pacientes ligados al mismo usuario ni un instante.
        if (source.getUser() != null) {
            User account = source.getUser();
            source.setUser(null);
            patientRepository.saveAndFlush(source);
            target.setUser(account);
        }
        // La información médica más reciente gana (la del destino, salvo que la del origen sea
        // más nueva o el destino nunca la haya capturado).
        boolean sourceMedicalIsNewer = source.getMedicalInfoUpdatedAt() != null
                && (target.getMedicalInfoUpdatedAt() == null || source.getMedicalInfoUpdatedAt().isAfter(target.getMedicalInfoUpdatedAt()));
        if (sourceMedicalIsNewer) {
            target.setAllergies(source.getAllergies());
            target.setBloodType(source.getBloodType());
            target.setBloodTypeOther(source.getBloodTypeOther());
            target.setMedicalInfoUpdatedBy(source.getMedicalInfoUpdatedBy());
            target.setMedicalInfoUpdatedAt(source.getMedicalInfoUpdatedAt());
        }
        if ((target.getEmail() == null || target.getEmail().isBlank()) && source.getEmail() != null) {
            target.setEmail(source.getEmail());
        }
        target.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        patientRepository.saveAndFlush(target);

        // Citas (y con ellas las recetas, que cuelgan de la cita) pasan al paciente destino.
        appointmentRepository.reassignPatient(source, target);

        // El UPDATE masivo limpia el contexto de persistencia: se vuelve a leer antes de cerrar el origen.
        Patient closedSource = getEntityById(sourceId);
        closedSource.setIsActive(false);
        closedSource.setDeletedAt(LocalDateTime.now());
        closedSource.setDeletedBy(SecurityUtils.getCurrentUserOrNull());
        patientRepository.save(closedSource);
        return patientMapper.toResponse(getEntityById(targetId));
    }

    @Override
    @Transactional(readOnly = true)
    public PatientResponse getOwn() {
        return patientMapper.toResponse(findOwnPatient(requireCurrentUser()));
    }

    /** Sin guardar -- deja que el llamador decida cuándo persistir (create() hace un solo
     * save() con todo lo demás; applyMedicalInfo() sí guarda, para el resto de los casos
     * donde el Patient ya existe). Valida que "Otro" venga con su detalle: sin esto, un
     * tipo de sangre "Otro" sin especificar quedaría tan vacío como no haber preguntado nada. */
    private void setMedicalInfoFields(Patient patient, MedicalInfoRequest request, User modifiedBy) {
        if (request.getBloodType() == BloodType.OTHER
                && (request.getBloodTypeOther() == null || request.getBloodTypeOther().isBlank())) {
            throw new BusinessException("Especifica el tipo de sangre en 'Otro'");
        }
        patient.setAllergies(request.getAllergies().trim());
        patient.setBloodType(request.getBloodType());
        patient.setBloodTypeOther(request.getBloodType() == BloodType.OTHER ? request.getBloodTypeOther().trim() : null);
        patient.setMedicalInfoUpdatedBy(modifiedBy);
        patient.setMedicalInfoUpdatedAt(LocalDateTime.now());
    }

    private Patient findOwnPatient(User user) {
        return patientRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe un paciente ligado a esta cuenta"));
    }

    private User requireCurrentUser() {
        User user = SecurityUtils.getCurrentUserOrNull();
        if (user == null) {
            throw new ResourceNotFoundException("No hay sesión activa");
        }
        return user;
    }
}
