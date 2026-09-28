package com.hospital.citas.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.TenantId;

/**
 * Base de todo dato que pertenece a un hospital. {@code @TenantId} hace que Hibernate:
 * <ul>
 *   <li>filtre cada consulta por el hospital actual ({@link TenantContext}), y</li>
 *   <li>llene {@code hospitalId} solo al insertar (con el hospital actual; en modo ROOT
 *       respeta el valor que traiga la entidad).</li>
 * </ul>
 * {@link TenantGuard} cubre lo que el filtro no ve: cargas por id y relaciones LAZY.
 * {@code hospitalId} solo es null en el usuario SUPER_ADMIN (no pertenece a ningún hospital).
 */
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
@EntityListeners(TenantGuard.class)
public abstract class TenantEntity {

    @TenantId
    @Column(name = "hospital_id", updatable = false)
    private Long hospitalId;
}
