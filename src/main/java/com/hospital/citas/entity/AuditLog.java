package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Bitácora de acceso a datos sensibles: quién vio, descargó o cambió el expediente de un
 * paciente, una receta o una solicitud ARCO, cuándo y desde dónde. No se edita ni se borra
 * desde la app (constancia ante una auditoría o una queja de un paciente).
 */
@Entity
@Table(name = "audit_logs")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AuditLog extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_name", length = 150)
    private String userName;

    @Column(name = "user_role", length = 30)
    private String userRole;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "entity_type", length = 30)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(length = 255)
    private String path;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
