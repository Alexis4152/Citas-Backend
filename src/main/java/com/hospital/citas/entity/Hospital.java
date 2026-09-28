package com.hospital.citas.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Hospital o consultorio cliente de la plataforma (el "tenant"). Todo dato de negocio
 * (doctores, pacientes, citas, cobros, configuración...) pertenece a uno solo, vía la columna
 * {@code hospital_id} (ver {@code TenantEntity}). Sus pacientes entran por
 * {@code <frontend>/c/<slug>}. Lo administra solo el SUPER_ADMIN (Nexora).
 * <p>
 * Llaves de OpenPay por hospital: el dinero de los pagos en línea le llega directo a su
 * cuenta. Sin llaves, sus pacientes solo pueden pagar en recepción.
 */
@Entity
@Table(name = "hospitals")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Hospital {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    /** Identificador del link público: solo minúsculas, números y guiones. */
    @Column(nullable = false, unique = true, length = 60)
    private String slug;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "openpay_merchant_id", length = 100)
    private String openpayMerchantId;

    @Column(name = "openpay_private_key", length = 100)
    private String openpayPrivateKey;

    @Column(name = "openpay_public_key", length = 100)
    private String openpayPublicKey;

    @Column(name = "openpay_production", nullable = false)
    @Builder.Default
    private Boolean openpayProduction = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Tiene lo necesario para cobrar en línea con su propia cuenta de OpenPay. */
    public boolean hasOpenpay() {
        return notBlank(openpayMerchantId) && notBlank(openpayPrivateKey) && notBlank(openpayPublicKey);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
