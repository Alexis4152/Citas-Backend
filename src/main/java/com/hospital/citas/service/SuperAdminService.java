package com.hospital.citas.service;

import com.hospital.citas.dto.request.HospitalAdminRequest;
import com.hospital.citas.dto.request.HospitalCreateRequest;
import com.hospital.citas.dto.request.HospitalUpdateRequest;
import com.hospital.citas.dto.response.HospitalResponse;
import com.hospital.citas.dto.response.UserResponse;
import com.hospital.citas.entity.EmailConfig;
import com.hospital.citas.entity.Hospital;
import com.hospital.citas.entity.HospitalConfig;
import com.hospital.citas.entity.Role;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.DuplicateResourceException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.UserMapper;
import com.hospital.citas.repository.*;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.tenant.HospitalDirectory;
import com.hospital.citas.tenant.TenantContext;
import com.hospital.citas.util.TempPasswordGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Panel de plataforma (Nexora): alta y administración de hospitales/consultorios. Corre en
 * ROOT (el SUPER_ADMIN no pertenece a ningún hospital), así que todo lo que se inserta para un
 * hospital lleva su {@code hospitalId} explícito; los conteos y listados por hospital se leen
 * con {@link TenantContext#callAs} en el contexto de ese hospital -- siempre FUERA de una
 * transacción: dentro de una, la sesión (abierta en ROOT) ignoraría el cambio de hospital y
 * contaría los datos de todos. Por eso las escrituras van en {@code tx.execute(...)} y la
 * respuesta se arma después del commit.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminService {

    private final HospitalRepository hospitalRepository;
    private final HospitalConfigRepository hospitalConfigRepository;
    private final EmailConfigRepository emailConfigRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final HospitalDirectory hospitalDirectory;
    private final TransactionTemplate tx;

    @Value("${app.frontend-url:http://localhost:5175}")
    private String frontendUrl;

    public List<HospitalResponse> list() {
        return hospitalRepository.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    public HospitalResponse get(Long id) {
        return toResponse(find(id));
    }

    /** Hospital nuevo con su configuración inicial y su primer ADMIN (contraseña temporal). */
    public HospitalResponse create(HospitalCreateRequest request) {
        String temporaryPassword = TempPasswordGenerator.generate();
        Hospital hospital = tx.execute(status -> insertHospital(request, temporaryPassword));
        hospitalDirectory.evict();
        HospitalResponse response = toResponse(hospital);
        response.setTemporaryPassword(temporaryPassword);
        return response;
    }

    private Hospital insertHospital(HospitalCreateRequest request, String temporaryPassword) {
        String slug = request.getSlug().trim().toLowerCase();
        if (hospitalRepository.existsBySlug(slug)) {
            throw new DuplicateResourceException("Ya existe un hospital con el enlace /c/" + slug);
        }
        Hospital hospital = hospitalRepository.save(Hospital.builder()
                .name(request.getName().trim())
                .slug(slug)
                .build());
        Long hospitalId = hospital.getId();

        HospitalConfig config = HospitalConfig.builder()
                .name(hospital.getName())
                .primaryColor("#0F766E")
                .updatedAt(LocalDateTime.now())
                .build();
        config.setHospitalId(hospitalId);
        hospitalConfigRepository.save(config);

        // Correo apagado: el ADMIN del hospital captura su propio SMTP desde su panel.
        EmailConfig emailConfig = EmailConfig.builder()
                .enabled(false)
                .smtpHost("smtp.gmail.com")
                .smtpPort(587)
                .updatedAt(LocalDateTime.now())
                .build();
        emailConfig.setHospitalId(hospitalId);
        emailConfigRepository.save(emailConfig);

        createAdmin(hospitalId, request.getAdminFirstName(), request.getAdminLastName(), request.getAdminEmail(), temporaryPassword);
        return hospital;
    }

    public HospitalResponse update(Long id, HospitalUpdateRequest request) {
        Hospital saved = tx.execute(status -> applyUpdate(id, request));
        hospitalDirectory.evict();
        return toResponse(saved);
    }

    private Hospital applyUpdate(Long id, HospitalUpdateRequest request) {
        Hospital hospital = find(id);
        String slug = request.getSlug().trim().toLowerCase();
        if (!slug.equals(hospital.getSlug()) && hospitalRepository.existsBySlug(slug)) {
            throw new DuplicateResourceException("Ya existe un hospital con el enlace /c/" + slug);
        }
        hospital.setName(request.getName().trim());
        hospital.setSlug(slug);
        hospital.setIsActive(request.getIsActive());
        hospital.setOpenpayProduction(request.getOpenpayProduction());
        if (request.isClearOpenpay()) {
            hospital.setOpenpayMerchantId(null);
            hospital.setOpenpayPublicKey(null);
            hospital.setOpenpayPrivateKey(null);
        } else {
            hospital.setOpenpayMerchantId(blankToNull(request.getOpenpayMerchantId()));
            hospital.setOpenpayPublicKey(blankToNull(request.getOpenpayPublicKey()));
            // Write-only: vacío = conservar la guardada.
            if (request.getOpenpayPrivateKey() != null && !request.getOpenpayPrivateKey().isBlank()) {
                hospital.setOpenpayPrivateKey(request.getOpenpayPrivateKey().trim());
            }
        }
        return hospitalRepository.save(hospital);
    }

    /** Otro ADMIN para un hospital existente (ej. si el primero se fue o perdió su acceso). */
    public HospitalResponse addAdmin(Long id, HospitalAdminRequest request) {
        Hospital hospital = find(id);
        String temporaryPassword = TempPasswordGenerator.generate();
        tx.executeWithoutResult(status ->
                createAdmin(hospital.getId(), request.getFirstName(), request.getLastName(), request.getEmail(), temporaryPassword));
        HospitalResponse response = toResponse(hospital);
        response.setTemporaryPassword(temporaryPassword);
        return response;
    }

    private void createAdmin(Long hospitalId, String firstName, String lastName, String email, String temporaryPassword) {
        String cleanEmail = email.trim().toLowerCase();
        if (userRepository.existsByEmailAndHospitalId(cleanEmail, hospitalId)) {
            throw new DuplicateResourceException("Ese hospital ya tiene una cuenta con el correo " + cleanEmail);
        }
        Role adminRole = roleRepository.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("Rol ADMIN no configurado"));
        User admin = User.builder()
                .hospitalId(hospitalId)
                .email(cleanEmail)
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .firstName(firstName.trim())
                .lastName(lastName.trim())
                .role(adminRole)
                .mustChangePassword(true)
                .createdBy(SecurityUtils.getCurrentUserOrNull())
                .build();
        userRepository.save(admin);
    }

    private HospitalResponse toResponse(Hospital hospital) {
        long hospitalId = hospital.getId();
        long doctors = TenantContext.callAs(hospitalId, doctorRepository::countByIsActiveTrue);
        long patients = TenantContext.callAs(hospitalId, patientRepository::count);
        long appointments = TenantContext.callAs(hospitalId, appointmentRepository::count);
        List<UserResponse> admins = TenantContext.callAs(hospitalId,
                () -> userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN).stream().map(userMapper::toResponse).toList());
        return HospitalResponse.builder()
                .id(hospital.getId())
                .name(hospital.getName())
                .slug(hospital.getSlug())
                .publicUrl(frontendUrl.replaceAll("/+$", "") + "/c/" + hospital.getSlug())
                .isActive(hospital.getIsActive())
                .openpayConfigured(hospital.hasOpenpay())
                .openpayMerchantId(hospital.getOpenpayMerchantId())
                .openpayPublicKey(hospital.getOpenpayPublicKey())
                .openpayPrivateKeyConfigured(hospital.getOpenpayPrivateKey() != null && !hospital.getOpenpayPrivateKey().isBlank())
                .openpayProduction(hospital.getOpenpayProduction())
                .doctors(doctors)
                .patients(patients)
                .appointments(appointments)
                .admins(admins)
                .createdAt(hospital.getCreatedAt())
                .build();
    }

    private Hospital find(Long id) {
        return hospitalRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Hospital no encontrado: " + id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
