package com.hospital.citas.dto.request;

import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Invitado que perdió su código de cancelación: los mismos datos que dio al agendar. */
@Data
public class GuestLookupRequest {

    @NotBlank(message = "El teléfono es obligatorio")
    @Pattern(regexp = ValidationPatterns.PHONE_REGEXP, message = ValidationPatterns.PHONE_MESSAGE)
    private String phone;

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    @Size(max = 100, message = "El apellido no puede superar 100 caracteres")
    private String lastName;

    /** Opcionales, y van juntos: con la fecha y hora exactas de la cita el invitado que NO dejó
     * correo puede entrar a su cita sin recibir nada por correo (son datos que solo él conoce,
     * además de teléfono y nombre). Sin ellos, los enlaces se mandan al correo registrado. */
    private java.time.LocalDate appointmentDate;
    private java.time.LocalTime startTime;
}
