package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Archivo de una solicitud ARCO (identificación, reporte de Acceso, constancia), cifrado. */
@Entity
@Table(name = "arco_documents")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ArcoDocument extends TenantEntity {

    public static final String IDENTITY = "IDENTITY";
    public static final String INFO = "INFO";
    public static final String DELIVERY = "DELIVERY";
    public static final String CONSTANCIA = "CONSTANCIA";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "arco_request_id")
    private ArcoRequest arcoRequest;

    @Column(nullable = false, length = 15)
    private String kind;

    @Column(nullable = false, length = 200)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 80)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Integer sizeBytes;

    /** Contenido cifrado (AES-256-GCM); null cuando ya se borró. */
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data", columnDefinition = "bytea")
    private byte[] data;

    @Column(name = "uploaded_at", nullable = false)
    @Builder.Default
    private LocalDateTime uploadedAt = LocalDateTime.now();

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
