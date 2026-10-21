package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Constancia de consentimiento: quién aceptó (o retiró) qué, sobre qué versión del aviso de
 * privacidad, cuándo y por qué medio. Nunca se edita: cada cambio es un renglón nuevo.
 */
@Entity
@Table(name = "consent_records")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ConsentRecord extends TenantEntity {

    /** Aviso de privacidad (finalidades primarias). */
    public static final String NOTICE = "NOTICE";
    /** Consentimiento expreso para datos de salud (datos sensibles). */
    public static final String SENSITIVE = "SENSITIVE";
    public static final String REMINDERS = "REMINDERS";
    public static final String NEWS = "NEWS";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "notice_version", nullable = false)
    private Integer noticeVersion;

    @Column(nullable = false, length = 20)
    private String scope;

    @Column(nullable = false)
    private Boolean granted;

    /** WEB_BOOKING · REGISTER · RECEPTION · PRIVACY_CENTER · ARCO. */
    @Column(nullable = false, length = 20)
    private String channel;

    @Column(length = 64)
    private String ip;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
