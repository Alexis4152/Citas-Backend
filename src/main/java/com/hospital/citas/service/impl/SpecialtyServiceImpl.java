package com.hospital.citas.service.impl;

import com.hospital.citas.dto.request.SpecialtyRequest;
import com.hospital.citas.dto.response.SpecialtyResponse;
import com.hospital.citas.entity.Specialty;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.DuplicateResourceException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.SpecialtyMapper;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.SpecialtyRepository;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.SpecialtyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SpecialtyServiceImpl implements SpecialtyService {

    private final SpecialtyRepository specialtyRepository;
    private final AppointmentRepository appointmentRepository;
    private final SpecialtyMapper specialtyMapper;

    @Override
    @Transactional(readOnly = true)
    public List<SpecialtyResponse> listActive() {
        return specialtyRepository.findByIsActiveTrue().stream().map(specialtyMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SpecialtyResponse> adminSearch(String query, Pageable pageable) {
        String q = query == null ? "" : query;
        return specialtyRepository.findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(q, q, pageable)
                .map(specialtyMapper::toResponse);
    }

    @Override
    @Transactional
    public SpecialtyResponse create(SpecialtyRequest request) {
        if (specialtyRepository.existsByNameIgnoreCase(request.getName())) {
            throw new DuplicateResourceException("Ya existe una especialidad con ese nombre");
        }
        Specialty specialty = new Specialty();
        specialtyMapper.applyRequest(specialty, request);
        specialty.setCreatedBy(SecurityUtils.getCurrentUserOrNull());
        specialty = specialtyRepository.save(specialty);
        return specialtyMapper.toResponse(specialty);
    }

    @Override
    @Transactional
    public SpecialtyResponse update(Long id, SpecialtyRequest request) {
        Specialty specialty = findActive(id);
        if (!specialty.getName().equalsIgnoreCase(request.getName())
                && specialtyRepository.existsByNameIgnoreCase(request.getName())) {
            throw new DuplicateResourceException("Ya existe una especialidad con ese nombre");
        }
        specialtyMapper.applyRequest(specialty, request);
        specialty.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        specialty = specialtyRepository.save(specialty);
        return specialtyMapper.toResponse(specialty);
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        Specialty specialty = findActive(id);
        LocalDateTime now = LocalDateTime.now();
        long upcoming = appointmentRepository.countUpcomingBySpecialty(id, now.toLocalDate(), now.toLocalTime());
        if (upcoming > 0) {
            throw new BusinessException("No se puede dar de baja la especialidad: tiene " + upcoming
                    + " cita(s) programada(s) pendientes. Reprográmalas o cancélalas primero.");
        }
        specialty.setIsActive(false);
        specialty.setDeletedAt(LocalDateTime.now());
        specialty.setDeletedBy(SecurityUtils.getCurrentUserOrNull());
        specialtyRepository.save(specialty);
    }

    private Specialty findActive(Long id) {
        return specialtyRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Especialidad no encontrada: " + id));
    }
}
