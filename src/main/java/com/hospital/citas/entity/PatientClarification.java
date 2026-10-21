package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Nota aclaratoria al expediente (Rectificación de algo clínico, que no se puede alterar). */
@Entity
@Table(name = "patient_clarifications")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PatientClarification extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "arco_request_id")
    private ArcoRequest arcoRequest;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
