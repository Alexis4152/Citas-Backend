package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Campos de auditoría y borrado lógico compartidos por las entidades administrables
 * (sedes, especialidades, doctores, pacientes, usuarios, citas). {@code createdBy}/
 * {@code updatedBy}/{@code deletedBy} siempre deben poblarse desde el usuario autenticado en
 * el contexto de seguridad (nunca desde un valor enviado por el frontend).
 * <p>
 * Usa {@code @SuperBuilder} (no {@code @Builder}) para que el builder de las subclases
 * incluya estos campos heredados; toda subclase debe anotarse también con
 * {@code @SuperBuilder @NoArgsConstructor}.
 */
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class AuditableEntity extends TenantEntity {

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id")
    private User updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by_user_id")
    private User deletedBy;

    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
