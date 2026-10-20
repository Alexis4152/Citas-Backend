package com.hospital.citas.service;

import com.hospital.citas.dto.request.SlotHoldRequest;
import com.hospital.citas.dto.response.SlotHoldResponse;
import com.hospital.citas.entity.Branch;
import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.SlotHold;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.exception.SlotHeldException;
import com.hospital.citas.exception.SlotUnavailableException;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.BranchRepository;
import com.hospital.citas.repository.DoctorRepository;
import com.hospital.citas.repository.SlotHoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Apartado temporal de horarios (ver {@link SlotHold}). Resuelve el caso "dos personas eligen el
 * mismo horario al mismo tiempo": la primera que lo elige lo aparta unos minutos mientras llena
 * sus datos, y a la segunda ya no se le ofrece (lo ve como "alguien lo está apartando"). Si la
 * primera no confirma, el apartado vence y el horario vuelve a estar libre.
 * <p>
 * El apartado NO sustituye las defensas al confirmar la cita (bloqueo pesimista, índice único y
 * restricción de traslape en {@code AppointmentServiceImpl}): solo evita que alguien llene todo
 * el formulario para enterarse al final de que el horario ya no estaba.
 * <p>
 * Abusos que se frenan: una pestaña solo aparta un horario a la vez (elegir otro suelta el
 * anterior); cada conexión (IP) tiene un tope de apartados vigentes; un apartado se puede
 * renovar, pero nunca más allá de {@code slot-hold-max-minutes} desde que se creó.
 */
@Service
@RequiredArgsConstructor
public class SlotHoldService {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final SlotHoldRepository slotHoldRepository;
    private final AppointmentRepository appointmentRepository;
    private final DoctorRepository doctorRepository;
    private final BranchRepository branchRepository;
    private final AvailabilityService availabilityService;
    private final AvailabilityEvents availabilityEvents;

    @Value("${app.appointments.slot-hold-minutes:10}")
    private int holdMinutes;
    @Value("${app.appointments.slot-hold-max-minutes:20}")
    private int maxHoldMinutes;
    @Value("${app.appointments.slot-hold-max-per-ip:3}")
    private int maxHoldsPerIp;

    /** Aparta (o renueva, si ya es de esta pestaña) el horario pedido. */
    @Transactional
    public SlotHoldResponse hold(SlotHoldRequest request, String clientIp) {
        Doctor doctor = doctorRepository.findByIdAndIsActiveTrue(request.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor no encontrado: " + request.getDoctorId()));
        Branch branch = branchRepository.findByIdAndIsActiveTrue(request.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Sede no encontrada: " + request.getBranchId()));
        if (doctor.getBranches().stream().noneMatch(b -> b.getId().equals(branch.getId()))) {
            throw new BusinessException("El doctor no atiende en la sede seleccionada");
        }
        LocalDate date = request.getDate();
        LocalTime start = request.getStartTime();
        // Mismas reglas que el agendado público: horario de la agenda, sin bloqueos, con anticipación.
        LocalTime end = availabilityService.resolveEndTimeOrThrow(doctor, branch, date, start);
        String holderKey = request.getHolderKey();
        // Apartados y agendados del mismo doctor, uno a la vez: dos personas que eligen el mismo
        // horario en el mismo instante nunca pasan juntas la revisión (el índice único es respaldo).
        doctorRepository.lockById(doctor.getId());
        LocalDateTime now = LocalDateTime.now();

        if (appointmentRepository.countOccupantsForSlot(doctor.getId(), date, start, end) > 0) {
            throw new SlotUnavailableException("Ese horario acaba de ser reservado por alguien más. Elige otro.");
        }

        SlotHold mine = null;
        for (SlotHold existing : slotHoldRepository.findActiveOverlapping(doctor.getId(), date, start, end, now)) {
            if (!existing.getHolderKey().equals(holderKey)) {
                throw heldByOther(existing.getExpiresAt());
            }
            if (existing.getStartTime().equals(start)) {
                mine = existing;
            }
        }

        // Una pestaña aparta un solo horario: al elegir otro, el anterior queda libre de inmediato.
        for (SlotHold previous : slotHoldRepository.findByHolderKey(holderKey)) {
            if (mine == null || !previous.getId().equals(mine.getId())) {
                slotHoldRepository.delete(previous);
            }
        }

        if (mine != null) {
            LocalDateTime cap = mine.getCreatedAt().plusMinutes(maxHoldMinutes);
            LocalDateTime renewed = now.plusMinutes(holdMinutes);
            mine.setExpiresAt(renewed.isAfter(cap) ? cap : renewed);
            mine.setBranchId(branch.getId());
            availabilityEvents.publishAfterCommit(doctor.getId());
            return toResponse(slotHoldRepository.save(mine), now);
        }

        if (clientIp != null && slotHoldRepository.countActiveByIpExcludingHolder(clientIp, holderKey, now) >= maxHoldsPerIp) {
            throw new BusinessException("Hay demasiados horarios apartados desde tu conexión. "
                    + "Termina de agendar o espera unos minutos.");
        }

        slotHoldRepository.deleteExpiredAt(doctor.getId(), date, start, now);
        SlotHold hold = SlotHold.builder()
                .doctorId(doctor.getId())
                .branchId(branch.getId())
                .holdDate(date)
                .startTime(start)
                .endTime(end)
                .holderKey(holderKey)
                .token(UUID.randomUUID())
                .clientIp(clientIp)
                .expiresAt(now.plusMinutes(holdMinutes))
                .createdAt(now)
                .build();
        try {
            hold = slotHoldRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException e) {
            // Otra pestaña lo apartó en el mismo instante (índice único ux_slot_holds_slot).
            throw heldByOther(now.plusMinutes(holdMinutes));
        }
        availabilityEvents.publishAfterCommit(doctor.getId());
        return toResponse(hold, now);
    }

    /** Suelta un apartado (quien apartó cambió de horario, cerró el formulario o lo canceló). */
    @Transactional
    public void release(UUID token) {
        slotHoldRepository.findByToken(token).ifPresent(hold -> {
            slotHoldRepository.delete(hold);
            availabilityEvents.publishAfterCommit(hold.getDoctorId());
        });
    }

    /**
     * Al confirmar una cita: falla si OTRA persona tiene apartado ese horario. Lo apartado por
     * quien confirma (mismo token o misma pestaña) no estorba. Corre dentro de la transacción
     * del agendado, junto al bloqueo pesimista de las citas.
     */
    // Sin @Transactional a propósito: corre dentro de la transacción del agendado, y una
    // excepción que cruzara un proxy transaccional la marcaría para rollback aunque se atrape.
    public void requireNotHeldByOthers(Long doctorId, LocalDate date, LocalTime start, LocalTime end,
                                       UUID holdToken, String holderKey) {
        Optional<SlotHold> other = findHeldByOthers(doctorId, date, start, end, holdToken, holderKey);
        if (other.isPresent()) {
            throw heldByOther(other.get().getExpiresAt());
        }
    }

    /** El apartado vigente de OTRA persona sobre ese rango, si lo hay. */
    public Optional<SlotHold> findHeldByOthers(Long doctorId, LocalDate date, LocalTime start, LocalTime end,
                                               UUID holdToken, String holderKey) {
        return slotHoldRepository.findActiveOverlapping(doctorId, date, start, end, LocalDateTime.now()).stream()
                .filter(hold -> !(holdToken != null && holdToken.equals(hold.getToken()))
                        && !(holderKey != null && holderKey.equals(hold.getHolderKey())))
                .findFirst();
    }

    /** La cita ya quedó confirmada: su apartado ya no hace falta. */
    @Transactional
    public void consume(UUID holdToken, String holderKey) {
        if (holdToken != null) {
            slotHoldRepository.findByToken(holdToken).ifPresent(slotHoldRepository::delete);
        }
        if (holderKey != null && !holderKey.isBlank()) {
            slotHoldRepository.findByHolderKey(holderKey).forEach(slotHoldRepository::delete);
        }
    }

    @Transactional(readOnly = true)
    public List<SlotHold> activeHolds(Long doctorId, LocalDate from, LocalDate to) {
        return slotHoldRepository.findActive(doctorId, from, to, LocalDateTime.now());
    }

    /** Limpieza periódica (TenantJobs): los vencidos ya no cuentan, pero no tiene caso guardarlos. */
    @Transactional
    public int purgeExpired() {
        LocalDateTime now = LocalDateTime.now();
        // Quien estaba viendo esos horarios los ve libres en cuanto vence el apartado.
        List<Long> doctors = slotHoldRepository.findDoctorIdsWithExpired(now);
        int removed = slotHoldRepository.deleteExpired(now);
        doctors.forEach(availabilityEvents::publishAfterCommit);
        return removed;
    }

    private SlotHeldException heldByOther(LocalDateTime until) {
        return new SlotHeldException("Alguien más está apartando este horario en este momento. "
                + "Si no lo confirma, se libera a las " + until.format(HOUR) + ". Elige otro horario o intenta después.", until);
    }

    private SlotHoldResponse toResponse(SlotHold hold, LocalDateTime now) {
        return SlotHoldResponse.builder()
                .token(hold.getToken())
                .doctorId(hold.getDoctorId())
                .branchId(hold.getBranchId())
                .date(hold.getHoldDate())
                .startTime(hold.getStartTime())
                .endTime(hold.getEndTime())
                .expiresAt(hold.getExpiresAt())
                .secondsLeft(Math.max(0, Duration.between(now, hold.getExpiresAt()).toSeconds()))
                .build();
    }
}
