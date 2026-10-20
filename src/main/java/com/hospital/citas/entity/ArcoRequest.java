package com.hospital.citas.entity;

import com.hospital.citas.enums.ArcoRequestStatus;
import com.hospital.citas.enums.ArcoRequestType;
import com.hospital.citas.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Solicitud de derechos ARCO (Acceso, Rectificación, Cancelación, Oposición) sobre los datos
 * personales que guarda UN hospital: cada hospital es el responsable de los datos de sus
 * pacientes, así que la atiende su ADMIN (no la plataforma, a diferencia de DemoPV).
 * <p>
 * Solo se acepta si el correo corresponde a alguien que el hospital tiene registrado: una cuenta
 * ({@link #requesterUser}) o un paciente sin cuenta, por ejemplo uno que agendó como invitado
 * ({@link #patient}). Así nadie la levanta sobre datos que el hospital no tiene.
 * <p>
 * La resolución es manual: el ADMIN la marca completada o rechazada con una nota, y la corrección
 * o el borrado real lo hace con criterio (el expediente clínico tiene plazos legales de guarda
 * que impiden borrarlo a petición). Para Acceso, el panel ofrece descargar los datos del paciente.
 */
@Entity
@Table(name = "arco_requests")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ArcoRequest extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_name", nullable = false, length = 150)
    private String requesterName;

    @Column(name = "requester_email", nullable = false, length = 150)
    private String requesterEmail;

    @Column(name = "requester_phone", length = 30)
    private String requesterPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ArcoRequestType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private ArcoRequestStatus status = ArcoRequestStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_user_id")
    private User requesterUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_user_id")
    private User resolvedBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Fecha límite para responder: 20 días hábiles desde que se recibió (art. 32 LFPDPPP). */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
