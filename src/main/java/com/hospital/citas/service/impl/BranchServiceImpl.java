package com.hospital.citas.service.impl;

import com.hospital.citas.dto.request.BranchRequest;
import com.hospital.citas.dto.response.BranchResponse;
import com.hospital.citas.entity.Branch;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.BranchMapper;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.entity.DoctorSchedule;
import com.hospital.citas.repository.BranchRepository;
import com.hospital.citas.repository.DoctorScheduleRepository;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.BranchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BranchServiceImpl implements BranchService {

    private final BranchRepository branchRepository;
    private final AppointmentRepository appointmentRepository;
    private final DoctorScheduleRepository doctorScheduleRepository;
    private final BranchMapper branchMapper;

    @Override
    @Transactional(readOnly = true)
    public List<BranchResponse> listActive() {
        return branchRepository.findByIsActiveTrue().stream().map(branchMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BranchResponse> adminSearch(String query, Pageable pageable) {
        String q = query == null ? "" : query;
        return branchRepository.findByNameContainingIgnoreCaseOrCityContainingIgnoreCase(q, q, pageable)
                .map(branchMapper::toResponse);
    }

    @Override
    @Transactional
    public BranchResponse create(BranchRequest request) {
        validateHours(request);
        Branch branch = new Branch();
        branchMapper.applyRequest(branch, request);
        branch.setCreatedBy(SecurityUtils.getCurrentUserOrNull());
        branch = branchRepository.save(branch);
        return branchMapper.toResponse(branch);
    }

    @Override
    @Transactional
    public BranchResponse update(Long id, BranchRequest request) {
        Branch branch = findActive(id);
        validateHours(request);
        boolean hoursChanged = !Objects.equals(branch.getOpenTime(), request.getOpenTime())
                || !Objects.equals(branch.getCloseTime(), request.getCloseTime());
        if (hoursChanged && request.getOpenTime() != null && request.getCloseTime() != null) {
            // Cambiar el horario de la sede no debe dejar citas programadas fuera de él...
            LocalDateTime now = LocalDateTime.now();
            long outside = appointmentRepository.countUpcomingOutsideHours(id, now.toLocalDate(), now.toLocalTime(),
                    request.getOpenTime(), request.getCloseTime());
            if (outside > 0) {
                throw new BusinessException("No se puede cambiar el horario de la sede: hay " + outside
                        + " cita(s) programada(s) fuera del nuevo horario. Reprográmalas o cancélalas primero.");
            }
            // ...y los horarios de los doctores en esa sede se recortan al nuevo rango.
            clampDoctorSchedules(id, request.getOpenTime(), request.getCloseTime());
        }
        branchMapper.applyRequest(branch, request);
        branch.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        branch = branchRepository.save(branch);
        return branchMapper.toResponse(branch);
    }

    private void validateHours(BranchRequest request) {
        LocalTime open = request.getOpenTime();
        LocalTime close = request.getCloseTime();
        if ((open == null) != (close == null)) {
            throw new BusinessException("Indica la hora de apertura y la de cierre, o deja ambas vacías");
        }
        if (open != null && !open.isBefore(close)) {
            throw new BusinessException("La hora de apertura debe ser anterior a la de cierre");
        }
    }

    /** Recorta a [open, close] los horarios activos de los doctores en esta sede; los que
     * quedan completamente fuera se desactivan. */
    private void clampDoctorSchedules(Long branchId, LocalTime open, LocalTime close) {
        for (DoctorSchedule schedule : doctorScheduleRepository.findByBranch_IdAndIsActiveTrue(branchId)) {
            LocalTime start = schedule.getStartTime().isBefore(open) ? open : schedule.getStartTime();
            LocalTime end = schedule.getEndTime().isAfter(close) ? close : schedule.getEndTime();
            if (start.equals(schedule.getStartTime()) && end.equals(schedule.getEndTime())) {
                continue;
            }
            if (!start.isBefore(end)) {
                schedule.setIsActive(false);
                schedule.setDeletedAt(LocalDateTime.now());
                schedule.setDeletedBy(SecurityUtils.getCurrentUserOrNull());
            } else {
                schedule.setStartTime(start);
                schedule.setEndTime(end);
                schedule.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
            }
            doctorScheduleRepository.save(schedule);
        }
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        Branch branch = findActive(id);
        LocalDateTime now = LocalDateTime.now();
        long upcoming = appointmentRepository.countUpcomingByBranch(id, now.toLocalDate(), now.toLocalTime());
        if (upcoming > 0) {
            throw new BusinessException("No se puede dar de baja la sede: tiene " + upcoming
                    + " cita(s) programada(s) pendientes. Reprográmalas o cancélalas primero.");
        }
        branch.setIsActive(false);
        branch.setDeletedAt(LocalDateTime.now());
        branch.setDeletedBy(SecurityUtils.getCurrentUserOrNull());
        branchRepository.save(branch);
    }

    private Branch findActive(Long id) {
        return branchRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sede no encontrada: " + id));
    }
}
