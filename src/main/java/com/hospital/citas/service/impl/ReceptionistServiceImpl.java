package com.hospital.citas.service.impl;

import com.hospital.citas.dto.request.ReceptionistCreateRequest;
import com.hospital.citas.dto.request.ReceptionistUpdateRequest;
import com.hospital.citas.dto.response.UserResponse;
import com.hospital.citas.entity.Role;
import com.hospital.citas.entity.Specialty;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.DuplicateResourceException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.UserMapper;
import com.hospital.citas.repository.RoleRepository;
import com.hospital.citas.repository.SpecialtyRepository;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.EmailService;
import com.hospital.citas.service.ReceptionistService;
import com.hospital.citas.util.TempPasswordGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReceptionistServiceImpl implements ReceptionistService {

    // Unicidad de teléfono solo entre staff (no contra pacientes -- ver UserRepository).
    private static final List<RoleName> STAFF_ROLES = List.of(RoleName.ADMIN, RoleName.DOCTOR, RoleName.RECEPTIONIST);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SpecialtyRepository specialtyRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final EmailService emailService;

    /** Nulo/vacío = recepcionista general, sin restricción. Valida que todos los ids
     * correspondan a especialidades reales -- un id inventado/borrado no debe colarse
     * silenciosamente (a diferencia de simplemente ignorarlo, lo cual dejaría a la
     * recepcionista con menos restricción de la que el ADMIN pidió). */
    private List<Specialty> resolveSpecialties(List<Long> specialtyIds) {
        if (specialtyIds == null || specialtyIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<Specialty> found = specialtyRepository.findAllById(specialtyIds);
        if (found.size() != specialtyIds.size()) {
            throw new BusinessException("Una o más especialidades seleccionadas no existen");
        }
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> search(String query, Pageable pageable) {
        return userRepository.searchByRole(RoleName.RECEPTIONIST, query, pageable).map(userMapper::toResponse);
    }

    @Override
    @Transactional
    public UserResponse create(ReceptionistCreateRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Ya existe una cuenta con ese correo");
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()
                && userRepository.existsByPhoneAndIsActiveTrueAndRole_NameIn(request.getPhone(), STAFF_ROLES)) {
            throw new DuplicateResourceException("Ya existe una cuenta de staff con ese teléfono");
        }
        Role receptionistRole = roleRepository.findByName(RoleName.RECEPTIONIST)
                .orElseThrow(() -> new IllegalStateException("Rol RECEPTIONIST no configurado"));

        // Igual que en el alta de doctor: la contraseña la genera el servidor, nunca el
        // ADMIN a mano (patrón 02).
        String temporaryPassword = TempPasswordGenerator.generate();

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(receptionistRole)
                .mustChangePassword(true)
                .specialties(resolveSpecialties(request.getSpecialtyIds()))
                .build();
        user.setCreatedBy(SecurityUtils.getCurrentUserOrNull());
        user = userRepository.save(user);

        emailService.sendTemporaryCredentials(user, temporaryPassword);

        UserResponse response = userMapper.toResponse(user);
        response.setTemporaryPassword(temporaryPassword);
        return response;
    }

    @Override
    @Transactional
    public UserResponse update(Long id, ReceptionistUpdateRequest request) {
        User user = findActiveReceptionist(id);
        if (request.getPhone() != null && !request.getPhone().isBlank()
                && userRepository.existsByPhoneAndIsActiveTrueAndRole_NameInAndIdNot(request.getPhone(), STAFF_ROLES, user.getId())) {
            throw new DuplicateResourceException("Ya existe una cuenta de staff con ese teléfono");
        }
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhone(request.getPhone());
        // Reemplaza la lista completa en vez de mutarla in-place (agregar/quitar a mano)
        // -- más simple y evita dejar residuos si el ADMIN quita todas las especialidades
        // (recepcionista vuelve a general).
        user.getSpecialties().clear();
        user.getSpecialties().addAll(resolveSpecialties(request.getSpecialtyIds()));
        user.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        user = userRepository.save(user);
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        User user = findActiveReceptionist(id);
        User currentUser = SecurityUtils.getCurrentUserOrNull();
        user.setIsActive(false);
        user.setDeletedAt(LocalDateTime.now());
        user.setDeletedBy(currentUser);
        userRepository.save(user);
    }

    // Solo permite editar/desactivar una cuenta que sea realmente RECEPTIONIST y esté activa
    // -- evita que este endpoint (pensado solo para recepcionistas) toque por id cualquier
    // otro usuario (ej. un ADMIN) que el llamador haya adivinado.
    private User findActiveReceptionist(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recepcionista no encontrado: " + id));
        if (user.getRole().getName() != RoleName.RECEPTIONIST || !Boolean.TRUE.equals(user.getIsActive())) {
            throw new ResourceNotFoundException("Recepcionista no encontrado: " + id);
        }
        return user;
    }
}
