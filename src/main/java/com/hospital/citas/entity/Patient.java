package com.hospital.citas.entity;

import com.hospital.citas.enums.BloodType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Un paciente puede o no tener una cuenta ({@link User}) asociada. Los pacientes agendados
 * por teléfono/recepción NUNCA obtienen una fila en {@code users} — solo existen aquí, con
 * {@code user = null}. Esto evita forzar a pacientes invitados a través de Spring Security.
 */
@Entity
@Table(name = "patients")
@Getter @Setter @SuperBuilder @NoArgsConstructor
public class Patient extends AuditableEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 30)
    private String phone;

    @Column(length = 150)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** "Ninguna" = respondió explícitamente que no tiene (distinto de null, que es un
     * registro de antes de este campo). Obligatorio en todo alta/agendado nuevo -- ver
     * MedicalInfoRequest. */
    // Datos personales y de contacto (opcionales): edad en la receta, dirección para el expediente.
    @Column(name = "birth_date")
    private java.time.LocalDate birthDate;

    @Column(length = 1)
    private String sex;

    @Column(length = 18)
    private String curp;

    @Column(name = "address_line", length = 200)
    private String addressLine;

    @Column(length = 120)
    private String neighborhood;

    @Column(length = 100)
    private String city;

    @Column(length = 80)
    private String state;

    @Column(name = "postal_code", length = 5)
    private String postalCode;

    @Column(name = "emergency_contact_name", length = 150)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 10)
    private String emergencyContactPhone;

    @Column(length = 150)
    private String allergies;

    @Enumerated(EnumType.STRING)
    @Column(name = "blood_type", length = 20)
    private BloodType bloodType;

    /** Solo tiene valor cuando {@code bloodType == OTHER}. */
    @Column(name = "blood_type_other", length = 50)
    private String bloodTypeOther;

    /** Quién capturó/modificó por última vez alergias/tipo de sangre y cuándo -- deliberadamente
     * SEPARADO de {@code updatedBy}/{@code updatedAt} de AuditableEntity, que se pisan con
     * cualquier otro cambio del paciente (ej. corregir el teléfono) y perderían el rastro
     * específico de quién tocó la información médica (importante para responsabilidad legal).
     * Nulo = lo reportó el propio paciente como invitado, sin cuenta identificada. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medical_info_updated_by_user_id")
    private User medicalInfoUpdatedBy;

    @Column(name = "medical_info_updated_at")
    private LocalDateTime medicalInfoUpdatedAt;

    /** Correo que tenía antes de la regla "un correo = un paciente por hospital" (duplicado
     * que recepción debe revisar o fusionar). Solo informativo: no se usa para buscar. */
    @Column(name = "email_moved", length = 150)
    private String emailMoved;

    /** ACTIVE · BLOCKED (Cancelación ARCO: identidad en resguardo mientras corre el plazo legal)
     * · ANONYMIZED (venció el plazo: ya no hay forma de identificarlo). */
    @Column(name = "privacy_status", nullable = false, length = 15)
    @lombok.Builder.Default
    private String privacyStatus = "ACTIVE";

    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    /** Hasta cuándo se conserva lo bloqueado (expediente clínico y datos fiscales). */
    @Column(name = "retain_until")
    private java.time.LocalDate retainUntil;

    @Column(name = "anonymized_at")
    private LocalDateTime anonymizedAt;

    /** Preferencias (derecho de Oposición/Limitación): recordatorios por correo de sus citas. */
    @Column(name = "notify_reminders", nullable = false)
    @lombok.Builder.Default
    private Boolean notifyReminders = true;

    /** Novedades y encuestas del hospital (finalidad secundaria: apagada si no la acepta). */
    @Column(name = "notify_news", nullable = false)
    @lombok.Builder.Default
    private Boolean notifyNews = false;

    public boolean isPrivacyActive() {
        return privacyStatus == null || "ACTIVE".equals(privacyStatus);
    }
}
