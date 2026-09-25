package com.hospital.citas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Receta médica emitida por un doctor durante/tras atender una cita. {@code content} es el
 * texto libre final (el doctor parte de su {@link Doctor#getPrescriptionTemplate()} y lo edita
 * para ese paciente) -- se guarda tal cual quedó, no una referencia a la plantilla, para que el
 * PDF de una receta vieja no cambie si el doctor edita su plantilla después.
 */
@Entity
@Table(name = "prescriptions")
@Getter @Setter @SuperBuilder @NoArgsConstructor
public class Prescription extends AuditableEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    /** Diagnóstico en texto libre (opcional); va antes de las indicaciones en el PDF. */
    @Column(columnDefinition = "TEXT")
    private String diagnosis;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** Una receta emitida nunca se borra ni se edita (es un documento médico): si tiene un
     * error se anula con motivo y se emite una nueva. Nulo = vigente. */
    @Column(name = "voided_at")
    private java.time.LocalDateTime voidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voided_by_user_id")
    private User voidedBy;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    public boolean isVoided() {
        return voidedAt != null;
    }
}
