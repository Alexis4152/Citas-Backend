package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Un paso en la vida de una solicitud ARCO (recibida, identidad verificada, respondida...). */
@Entity
@Table(name = "arco_events")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ArcoEvent extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "arco_request_id")
    private ArcoRequest arcoRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actor;

    /** Quién actuó, como texto ("El titular", "Sistema", nombre del admin). */
    @Column(name = "actor_label", length = 150)
    private String actorLabel;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
