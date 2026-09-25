package com.hospital.citas.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Alergias + tipo de sangre del paciente -- obligatorio en todo alta/agendado (invitado,
 * paciente registrado o dado de alta por recepción) porque el doctor lo necesita para
 * recetar con seguridad. Se anida en {@link PatientRequest}, {@link GuestAppointmentRequest}
 * y {@link AppointmentRequest} en vez de duplicar los 3 campos en cada uno.
 * <p>
 * {@code allergies} nunca es un texto libre "opcional": el frontend siempre manda algo --
 * "Ninguna" cuando el paciente respondió que no tiene, o el detalle capturado (máx 150) si
 * sí. Así se distingue una respuesta explícita de un registro viejo (anterior a este
 * campo) que quedó en null.
 */
@Data
public class MedicalInfoRequest {

    @NotBlank(message = "Indica si el paciente tiene alergias (o 'Ninguna' si no)")
    @Size(max = 150, message = "Las alergias no pueden superar 150 caracteres")
    private String allergies;

    @NotNull(message = "Selecciona el tipo de sangre")
    private com.hospital.citas.enums.BloodType bloodType;

    /** Obligatorio (validado en el service, no aquí, porque depende del valor de
     * {@code bloodType}) solo cuando {@code bloodType == OTHER}. */
    @Size(max = 100, message = "El detalle del tipo de sangre no puede superar 100 caracteres")
    private String bloodTypeOther;
}
