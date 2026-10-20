package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Apartado temporal de un horario mientras alguien termina de agendar (llena sus datos, elige
 * cómo pagar). Mientras no venza, ese horario no se ofrece a nadie más: se ve como "alguien lo
 * está apartando". Si quien apartó no confirma, vence solo (ver SlotHoldService) y el horario
 * vuelve a quedar libre. No es una cita: nunca bloquea un horario más de unos minutos.
 * <p>
 * {@code holderKey} identifica la pestaña del navegador que aparta (un aleatorio que genera el
 * frontend), para que esa misma pestaña pueda cambiar de horario sin chocar consigo misma.
 * {@code token} solo lo conoce quien apartó: con él confirma la cita o suelta el horario.
 */
@Entity
@Table(name = "slot_holds", uniqueConstraints = @UniqueConstraint(name = "ux_slot_holds_slot",
        columnNames = {"doctor_id", "hold_date", "start_time"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SlotHold extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "hold_date", nullable = false)
    private LocalDate holdDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "holder_key", nullable = false, length = 64)
    private String holderKey;

    @Column(nullable = false, unique = true)
    private UUID token;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean overlaps(LocalTime start, LocalTime end) {
        return startTime.isBefore(end) && endTime.isAfter(start);
    }
}
