package com.hospital.citas.service.impl;

import com.hospital.citas.dto.request.DoctorCreateRequest;
import com.hospital.citas.dto.request.DoctorProfileUpdateRequest;
import com.hospital.citas.dto.request.DoctorScheduleRequest;
import com.hospital.citas.dto.request.DoctorUpdateRequest;
import com.hospital.citas.dto.response.DoctorDetailResponse;
import com.hospital.citas.dto.response.DoctorSummaryResponse;
import com.hospital.citas.entity.*;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.DuplicateResourceException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.DoctorMapper;
import com.hospital.citas.repository.*;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.DoctorService;
import com.hospital.citas.service.DoctorSpecifications;
import com.hospital.citas.service.EmailService;
import com.hospital.citas.service.FileStorageService;
import com.hospital.citas.util.TempPasswordGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DoctorServiceImpl implements DoctorService {

    private static final long MAX_PHOTO_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED_PHOTO_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    // Unicidad de teléfono solo entre staff (no contra pacientes -- ver UserRepository).
    private static final List<RoleName> STAFF_ROLES = List.of(RoleName.ADMIN, RoleName.DOCTOR, RoleName.RECEPTIONIST);

    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SpecialtyRepository specialtyRepository;
    private final BranchRepository branchRepository;
    private final DoctorScheduleRepository doctorScheduleRepository;
    private final AppointmentRepository appointmentRepository;
    private final DoctorMapper doctorMapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final FileStorageService fileStorageService;

    @Override
    @Transactional(readOnly = true)
    public Page<DoctorSummaryResponse> search(Long specialtyId, Long branchId, Pageable pageable) {
        return doctorRepository.findAll(DoctorSpecifications.search(specialtyId, branchId), pageable)
                .map(doctorMapper::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public DoctorDetailResponse getDetail(Long id) {
        return doctorMapper.toDetail(findActive(id));
    }

    @Override
    @Transactional
    public DoctorDetailResponse adminCreate(DoctorCreateRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Ya existe una cuenta con ese correo");
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()
                && userRepository.existsByPhoneAndIsActiveTrueAndRole_NameIn(request.getPhone(), STAFF_ROLES)) {
            throw new DuplicateResourceException("Ya existe una cuenta de staff con ese teléfono");
        }
        Role doctorRole = roleRepository.findByName(RoleName.DOCTOR)
                .orElseThrow(() -> new IllegalStateException("Rol DOCTOR no configurado"));
        Specialty specialty = specialtyRepository.findByIdAndIsActiveTrue(request.getSpecialtyId())
                .orElseThrow(() -> new ResourceNotFoundException("Especialidad no encontrada: " + request.getSpecialtyId()));

        List<Branch> branches = new ArrayList<>(branchRepository.findAllById(request.getBranchIds()));
        if (branches.isEmpty()) {
            throw new BusinessException("Debe asignarse al menos una sede válida");
        }

        User currentUser = SecurityUtils.getCurrentUserOrNull();

        // El ADMIN ya no captura la contraseña a mano (patrón 02): se genera una temporal,
        // se fuerza su cambio en el primer login, y se comunica una sola vez (respuesta +
        // correo si está habilitado).
        String temporaryPassword = TempPasswordGenerator.generate();

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(doctorRole)
                .mustChangePassword(true)
                .build();
        user.setCreatedBy(currentUser);
        user = userRepository.save(user);

        Doctor doctor = Doctor.builder()
                .user(user)
                .specialty(specialty)
                .licenseNumber(request.getLicenseNumber())
                .bio(request.getBio())
                .photoUrl(request.getPhotoUrl())
                .consultationPrice(normalizePrice(request.getConsultationPrice()))
                .defaultSlotMinutes(request.getDefaultSlotMinutes() != null ? request.getDefaultSlotMinutes() : 30)
                .branches(branches)
                .build();
        doctor.setCreatedBy(currentUser);
        doctor = doctorRepository.save(doctor);

        if (request.getSchedules() != null) {
            validateNewSchedules(branches, request.getSchedules());
            for (DoctorScheduleRequest scheduleReq : request.getSchedules()) {
                Branch branch = branches.stream()
                        .filter(b -> b.getId().equals(scheduleReq.getBranchId()))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(
                                "La sede del horario debe estar entre las sedes asignadas al doctor"));
                DoctorSchedule schedule = DoctorSchedule.builder()
                        .doctor(doctor)
                        .branch(branch)
                        .dayOfWeek(scheduleReq.getDayOfWeek())
                        .startTime(scheduleReq.getStartTime())
                        .endTime(scheduleReq.getEndTime())
                        .slotMinutes(scheduleReq.getSlotMinutes())
                        .build();
                schedule.setCreatedBy(currentUser);
                doctorScheduleRepository.save(schedule);
            }
        }

        emailService.sendTemporaryCredentials(user, temporaryPassword);

        DoctorDetailResponse response = doctorMapper.toDetail(doctor);
        response.setTemporaryPassword(temporaryPassword);
        return response;
    }

    /** Los horarios que llegan en el alta siguen las mismas reglas que DoctorScheduleService:
     * inicio < fin, dentro del horario de la sede y sin traslapes entre sí el mismo día. */
    private void validateNewSchedules(List<Branch> branches, List<DoctorScheduleRequest> schedules) {
        for (int i = 0; i < schedules.size(); i++) {
            DoctorScheduleRequest s = schedules.get(i);
            if (!s.getStartTime().isBefore(s.getEndTime())) {
                throw new BusinessException("La hora de inicio debe ser anterior a la hora de fin");
            }
            branches.stream().filter(b -> b.getId().equals(s.getBranchId())).findFirst().ifPresent(branch -> {
                if (branch.getOpenTime() != null && branch.getCloseTime() != null
                        && (s.getStartTime().isBefore(branch.getOpenTime()) || s.getEndTime().isAfter(branch.getCloseTime()))) {
                    throw new BusinessException("La sede " + branch.getName() + " abre de " + branch.getOpenTime()
                            + " a " + branch.getCloseTime() + "; el horario del doctor debe quedar dentro de ese rango");
                }
            });
            for (int j = 0; j < i; j++) {
                DoctorScheduleRequest other = schedules.get(j);
                if (other.getDayOfWeek() == s.getDayOfWeek()
                        && s.getStartTime().isBefore(other.getEndTime()) && s.getEndTime().isAfter(other.getStartTime())) {
                    throw new BusinessException("Dos horarios del mismo día se traslapan (" + s.getDayOfWeek() + ")");
                }
            }
        }
    }

    @Override
    @Transactional
    public DoctorDetailResponse adminUpdate(Long id, DoctorUpdateRequest request) {
        Doctor doctor = findActive(id);
        Specialty specialty = specialtyRepository.findByIdAndIsActiveTrue(request.getSpecialtyId())
                .orElseThrow(() -> new ResourceNotFoundException("Especialidad no encontrada: " + request.getSpecialtyId()));
        List<Branch> branches = new ArrayList<>(branchRepository.findAllById(request.getBranchIds()));
        if (branches.isEmpty()) {
            throw new BusinessException("Debe asignarse al menos una sede válida");
        }

        User currentUser = SecurityUtils.getCurrentUserOrNull();
        User user = doctor.getUser();

        // Quitarle una sede al doctor no debe dejar citas programadas ahí.
        LocalDateTime now = LocalDateTime.now();
        List<Long> keptBranchIds = branches.stream().map(Branch::getId).toList();
        for (Branch previous : doctor.getBranches()) {
            if (!keptBranchIds.contains(previous.getId())
                    && appointmentRepository.countUpcomingByDoctorAndBranch(doctor.getId(), previous.getId(),
                            now.toLocalDate(), now.toLocalTime()) > 0) {
                throw new BusinessException("No se puede quitar la sede " + previous.getName()
                        + ": el doctor tiene citas programadas ahí. Reprográmalas o cancélalas primero.");
            }
        }

        if (request.getPhone() != null && !request.getPhone().isBlank()
                && userRepository.existsByPhoneAndIsActiveTrueAndRole_NameInAndIdNot(request.getPhone(), STAFF_ROLES, user.getId())) {
            throw new DuplicateResourceException("Ya existe una cuenta de staff con ese teléfono");
        }

        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhone(request.getPhone());
        user.setUpdatedBy(currentUser);
        userRepository.save(user);

        doctor.setSpecialty(specialty);
        doctor.setLicenseNumber(request.getLicenseNumber());
        doctor.setBio(request.getBio());
        doctor.setConsultationPrice(normalizePrice(request.getConsultationPrice()));
        doctor.setDefaultSlotMinutes(request.getDefaultSlotMinutes() != null ? request.getDefaultSlotMinutes() : 30);
        doctor.setBranches(branches);
        doctor.setUpdatedBy(currentUser);
        doctor = doctorRepository.save(doctor);

        return doctorMapper.toDetail(doctor);
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        Doctor doctor = findActive(id);
        User currentUser = SecurityUtils.getCurrentUserOrNull();
        LocalDateTime now = LocalDateTime.now();
        long upcoming = appointmentRepository.countUpcomingByDoctor(doctor.getId(), now.toLocalDate(), now.toLocalTime());
        if (upcoming > 0) {
            throw new BusinessException("No se puede dar de baja al doctor: tiene " + upcoming
                    + " cita(s) programada(s) pendientes. Reprográmalas o cancélalas primero.");
        }

        doctor.setIsActive(false);
        doctor.setDeletedAt(LocalDateTime.now());
        doctor.setDeletedBy(currentUser);
        doctorRepository.save(doctor);

        User user = doctor.getUser();
        user.setIsActive(false);
        user.setDeletedAt(LocalDateTime.now());
        user.setDeletedBy(currentUser);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public DoctorDetailResponse updatePhoto(Long doctorId, MultipartFile photo) {
        Doctor doctor = findActive(doctorId);
        String relativePath = fileStorageService.store(photo, "doctors", MAX_PHOTO_BYTES, ALLOWED_PHOTO_EXTENSIONS);
        doctor.setPhotoUrl(relativePath);
        doctor.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        doctor = doctorRepository.save(doctor);
        return doctorMapper.toDetail(doctor);
    }

    @Override
    @Transactional(readOnly = true)
    public DoctorDetailResponse getOwnProfile() {
        return doctorMapper.toDetail(getOwnDoctorEntity());
    }

    @Override
    @Transactional
    public DoctorDetailResponse updateOwnProfile(DoctorProfileUpdateRequest request) {
        Doctor doctor = getOwnDoctorEntity();
        doctor.setBio(request.getBio());
        doctor.setPhotoUrl(request.getPhotoUrl());
        doctor.setPrescriptionTemplate(request.getPrescriptionTemplate());
        doctor.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        doctor = doctorRepository.save(doctor);
        return doctorMapper.toDetail(doctor);
    }

    /** El doctor define el precio de SUS consultas. Solo afecta a las citas que se agenden desde
     * ahora (cada cita conserva el precio que tenía al agendarse). Nulo o 0 = sin precio. */
    @Override
    @Transactional
    public DoctorDetailResponse updateOwnPrice(java.math.BigDecimal price) {
        Doctor doctor = getOwnDoctorEntity();
        doctor.setConsultationPrice(normalizePrice(price));
        doctor.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        return doctorMapper.toDetail(doctorRepository.save(doctor));
    }

    private java.math.BigDecimal normalizePrice(java.math.BigDecimal price) {
        return price == null || price.signum() <= 0 ? null : price.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Override
    @Transactional(readOnly = true)
    public Doctor getOwnDoctorEntity() {
        User currentUser = SecurityUtils.getCurrentUserOrNull();
        if (currentUser == null) {
            throw new ResourceNotFoundException("No hay sesión activa");
        }
        return doctorRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe un perfil de doctor para este usuario"));
    }

    private Doctor findActive(Long id) {
        return doctorRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor no encontrado: " + id));
    }
}
