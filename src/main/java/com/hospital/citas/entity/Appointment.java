package com.hospital.citas.entity;

import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.BloodType;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "appointments")
@Getter @Setter @SuperBuilder @NoArgsConstructor
public class Appointment extends AuditableEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppointmentStatus status;

    @Column(name = "reason_for_visit", columnDefinition = "TEXT")
    private String reasonForVisit;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_user_id")
    private User cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** true cuando el doctor liberó manualmente el espacio tras una cancelación con >=24h. */
    @Column(name = "slot_released", nullable = false)
    @Builder.Default
    private Boolean slotReleased = false;

    /** Quién liberó el espacio (doctor o recepción/admin) -- control de auditoría, ver
     * AppointmentServiceImpl.releaseSlot. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "released_by_user_id")
    private User releasedBy;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /**
     * Calculado y fijado UNA VEZ al momento de la cancelación (horas entre "ahora" y el
     * inicio original de la cita >= 24). Se guarda en vez de recalcularse después, porque
     * para entonces la fecha de la cita ya pudo haber pasado.
     */
    @Column(name = "release_eligible")
    private Boolean releaseEligible;

    /** Receptionist/admin que agendó la cita por teléfono; null si el paciente se auto-agendó. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id_appt")
    private User createdByUser;

    @Column(name = "cancel_token", nullable = false, unique = true)
    @Builder.Default
    private UUID cancelToken = UUID.randomUUID();

    /**
     * Alergias/tipo de sangre del paciente copiadas AL MOMENTO DE AGENDAR esta cita --
     * separadas a propósito de {@code patient.allergies}/{@code patient.bloodType}, que
     * siguen cambiando con cada cita nueva del mismo paciente. Sin esta copia, el comprobante
     * de una cita vieja mostraría el dato más reciente del paciente (que pudo cambiar
     * después) en vez del que era cierto cuando ocurrió esa cita en particular.
     */
    @Column(name = "allergies_snapshot", length = 150)
    private String allergiesSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "blood_type_snapshot", length = 20)
    private BloodType bloodTypeSnapshot;

    @Column(name = "blood_type_other_snapshot", length = 50)
    private String bloodTypeOtherSnapshot;

    /** Cuándo se mandó el recordatorio automático de 2 horas antes (null = aún no se manda) --
     * ver AppointmentServiceImpl#sendTwoHourReminders. */
    @Column(name = "reminder_2h_sent_at")
    private LocalDateTime reminder2hSentAt;

    /** Bloqueo optimista: si dos personas actúan sobre la misma cita a la vez (ej. recepción
     * la cancela mientras el doctor la marca atendida), la segunda en guardar recibe un error
     * claro en vez de pisar en silencio el cambio de la primera. */
    @Version
    private Long version;

    /** Cuándo registró su llegada el paciente (recepción o doctor) -- una cita con llegada
     * registrada nunca se marca automáticamente como "no asistió". */
    @Column(name = "arrived_at")
    private LocalDateTime arrivedAt;

    /** Sobrecupo: cita extra que recepción/admin empalma a propósito sobre un horario ya
     * ocupado (urgencia, paciente sin cita). Queda fuera de las restricciones de unicidad de
     * BD (ver db/17_functional_gaps.sql). */
    @Column(nullable = false)
    @Builder.Default
    private Boolean overbooked = false;

    /** Precio de la consulta AL MOMENTO de agendar (foto del precio del doctor: si él lo cambia
     * después, esta cita conserva el que se le dijo al paciente). Nulo = el doctor no tenía
     * precio definido y recepción captura el monto al cobrar. */
    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 15)
    @Builder.Default
    private com.hospital.citas.enums.AppointmentPaymentStatus paymentStatus = com.hospital.citas.enums.AppointmentPaymentStatus.UNPAID;
}
