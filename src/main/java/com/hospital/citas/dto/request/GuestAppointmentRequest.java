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
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    @Size(max = 100, message = "El apellido no puede superar 100 caracteres")
    private String lastName;

    @NotBlank(message = "El teléfono es obligatorio")
    @Pattern(regexp = ValidationPatterns.PHONE_REGEXP, message = ValidationPatterns.PHONE_MESSAGE)
    private String phone;

    @Email(message = "El correo no es válido")
    private String email;

    @NotNull(message = "El doctor es obligatorio")
    private Long doctorId;

    @NotNull(message = "La sede es obligatoria")
    private Long branchId;

    @NotNull(message = "La fecha es obligatoria")
    private LocalDate appointmentDate;

    @NotNull(message = "La hora es obligatoria")
    private LocalTime startTime;

    private String reasonForVisit;

    @NotNull(message = "Alergias y tipo de sangre son obligatorios")
    @Valid
    private MedicalInfoRequest medicalInfo;
}
