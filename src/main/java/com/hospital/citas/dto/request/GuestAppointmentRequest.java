package com.hospital.citas.dto.request;

import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** Agendado sin cuenta: solo nombre + teléfono son obligatorios, el correo es opcional. */
@Data
public class GuestAppointmentRequest {

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.NAME_REGEXP, message = com.hospital.citas.util.ValidationPatterns.NAME_MESSAGE)
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    @Size(max = 100, message = "El apellido no puede superar 100 caracteres")
    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.NAME_REGEXP, message = com.hospital.citas.util.ValidationPatterns.NAME_MESSAGE)
    private String lastName;

    @NotBlank(message = "El teléfono es obligatorio")
    @Pattern(regexp = ValidationPatterns.PHONE_REGEXP, message = ValidationPatterns.PHONE_MESSAGE)
    private String phone;

    @Email(regexp = com.hospital.citas.util.ValidationPatterns.EMAIL_REGEXP, message = "El correo no es válido")
    private String email;

    @NotNull(message = "El doctor es obligatorio")
    private Long doctorId;

    @NotNull(message = "La sede es obligatoria")
    private Long branchId;

    @NotNull(message = "La fecha es obligatoria")
    @jakarta.validation.constraints.FutureOrPresent(message = "La fecha de la cita no puede ser pasada")
    private LocalDate appointmentDate;

    @NotNull(message = "La hora es obligatoria")
    private LocalTime startTime;

    @Size(max = 1000, message = "El motivo no puede superar 1000 caracteres")
    private String reasonForVisit;

    @NotNull(message = "Alergias y tipo de sangre son obligatorios")
    @Valid
    private MedicalInfoRequest medicalInfo;

    /** Apartado temporal del horario (ver SlotHold), si quien agenda lo apartó al elegirlo. */
    private java.util.UUID holdToken;

    /** Pestaña del navegador que apartó el horario (ver SlotHoldRequest#holderKey). */
    @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9-]{8,64}$", message = "Identificador de sesión de agendado inválido")
    private String holderKey;

    /** Acepta el aviso de privacidad y da su consentimiento expreso para el tratamiento de sus
     * datos de salud (datos sensibles). Obligatorio: queda la constancia con la versión del aviso. */
    private Boolean acceptPrivacyNotice;

    @jakarta.validation.constraints.AssertTrue(message = "Debes aceptar el aviso de privacidad y el tratamiento de tus datos de salud")
    public boolean isPrivacyNoticeAccepted() {
        return Boolean.TRUE.equals(acceptPrivacyNotice);
    }

}
