package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Bloqueo de datos (Cancelación ARCO): la identidad y el contacto del paciente se separan de su
 * expediente y se guardan aquí, cifrados, solo mientras la ley obliga a conservarlos. Al vencer
 * {@link #retainUntil} se borra el contenido y el expediente queda anónimo para siempre.
 */
@Entity
@Table(name = "privacy_vault")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PrivacyVault extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "arco_request_id")
    private ArcoRequest arcoRequest;

    /** JSON cifrado con los datos separados; null cuando ya se purgó. */
    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "retain_until", nullable = false)
    private LocalDate retainUntil;

    @Column(name = "purged_at")
    private LocalDateTime purgedAt;
}
