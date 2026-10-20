package com.hospital.citas.entity;

import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Fila única de configuración/branding del hospital, consumida dinámicamente por el frontend. */
@Entity
@Table(name = "hospital_config")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class HospitalConfig extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "primary_color", nullable = false, length = 10)
    private String primaryColor;

    @Column(length = 500)
    private String description;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Column(name = "contact_email", length = 150)
    private String contactEmail;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(name = "rfc", length = 13)
    private String rfc;

    @Column(name = "address_line", length = 200)
    private String addressLine;

    @Column(name = "neighborhood", length = 120)
    private String neighborhood;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 80)
    private String state;

    @Column(name = "postal_code", length = 5)
    private String postalCode;

    @Column(name = "website", length = 200)
    private String website;

    @Column(name = "whatsapp", length = 10)
    private String whatsapp;

    @Column(name = "opening_hours", length = 300)
    private String openingHours;

    /** Si está prendido, una cita programada sin llegada registrada se marca sola "no asistió"
     * {@link #noShowGraceMinutes} después de que termina. Apagado (el valor por defecto),
     * recepción la confirma a mano en "Cobrar" → "Confirma si llegó". */
    @Builder.Default
    @Column(name = "auto_no_show_enabled", nullable = false)
    private Boolean autoNoShowEnabled = false;

    @Builder.Default
    @Column(name = "no_show_grace_minutes", nullable = false)
    private Integer noShowGraceMinutes = 30;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id")
    private User updatedBy;
}
