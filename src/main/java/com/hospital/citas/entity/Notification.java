package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import com.hospital.citas.enums.NotificationType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Notificación en app para el doctor/admin cuando se agenda o cancela una cita (además del
 * correo). El panel la consulta por polling (ver NotificationController) para que no haga
 * falta refrescar la página -- no hay websockets en este proyecto.
 */
@Entity
@Table(name = "notifications")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Notification extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
