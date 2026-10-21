package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Enlace de un solo uso enviado por correo (p. ej. activar la cuenta y ligarla al expediente). */
@Entity
@Table(name = "email_tokens")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class EmailToken extends TenantEntity {

    public static final String ACCOUNT_ACTIVATION = "ACCOUNT_ACTIVATION";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 25)
    private String purpose;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
