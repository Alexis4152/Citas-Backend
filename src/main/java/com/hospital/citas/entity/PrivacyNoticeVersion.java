package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Versión publicada del aviso de privacidad: los datos con que se generó, para consultarla después. */
@Entity
@Table(name = "privacy_notice_versions")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PrivacyNoticeVersion extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "published_at", nullable = false)
    @Builder.Default
    private LocalDateTime publishedAt = LocalDateTime.now();

    /** JSON con los datos del hospital que alimentan el aviso en esa versión. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshot;
}
