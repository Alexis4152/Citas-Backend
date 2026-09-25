package com.hospital.citas.dto.request;

import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PatientRequest {

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

    /** Si viene en true, además del Patient se crea una cuenta (User rol PATIENT) con
     * contraseña temporal generada por el servidor (patrón 02, igual que Doctor/
     * Recepcionista) -- requiere que `email` venga capturado. */
    private Boolean createAccount;

    @NotNull(message = "Alergias y tipo de sangre son obligatorios")
    @Valid
    private MedicalInfoRequest medicalInfo;
}
