package com.hospital.citas.security;

import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.repository.DoctorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * El doctor también usa los módulos de Pacientes, Cobrar y Corte de caja (endpoints de
 * /api/reception/**), pero SOLO sobre lo suyo: los pacientes que atendió o dio de alta y sus
 * propias citas. Recepción y admin no tienen esta restricción (vacío = sin filtro).
 */
@Component
@RequiredArgsConstructor
public class DoctorScope {

    private final DoctorRepository doctorRepository;

    /** Id del perfil de doctor de quien actúa, o vacío si no es DOCTOR. */
    public Optional<Long> doctorIdOf(User user) {
        if (user == null || user.getRole().getName() != RoleName.DOCTOR) {
            return Optional.empty();
        }
        return Optional.of(doctorRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe un perfil de doctor para este usuario"))
                .getId());
    }

    /** Un doctor solo puede operar sobre citas propias; recepción/admin pasan sin revisar. */
    public void requireOwnDoctor(User user, Doctor doctor, String action) {
        doctorIdOf(user).ifPresent(ownId -> {
            if (!ownId.equals(doctor.getId())) {
                throw new BusinessException("Solo puedes " + action + " tus propias citas");
            }
        });
    }
}
