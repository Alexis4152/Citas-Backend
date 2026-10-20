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

    /** Si viene en true, además del Patient se crea una cuenta (User rol PATIENT) con
     * contraseña temporal generada por el servidor (patrón 02, igual que Doctor/
     * Recepcionista) -- requiere que `email` venga capturado. */
    private Boolean createAccount;

    @NotNull(message = "Alergias y tipo de sangre son obligatorios")
    @Valid
    private MedicalInfoRequest medicalInfo;

    @jakarta.validation.constraints.Past(message = "La fecha de nacimiento debe ser en el pasado")
    private java.time.LocalDate birthDate;

    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.SEX_REGEXP, message = "Sexo inválido")
    private String sex;

    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.CURP_REGEXP, message = "La CURP no es válida (18 caracteres, en mayúsculas)")
    private String curp;

    @jakarta.validation.constraints.Size(max = 200, message = "La calle y número no puede superar 200 caracteres")
    private String addressLine;

    @jakarta.validation.constraints.Size(max = 120, message = "La colonia no puede superar 120 caracteres")
    private String neighborhood;

    @jakarta.validation.constraints.Size(max = 100, message = "La ciudad no puede superar 100 caracteres")
    private String city;

    @jakarta.validation.constraints.Size(max = 80, message = "El estado no puede superar 80 caracteres")
    private String state;

    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.POSTAL_CODE_REGEXP, message = "El código postal debe tener 5 dígitos")
    private String postalCode;

    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.NAME_REGEXP, message = "El contacto de emergencia: " + com.hospital.citas.util.ValidationPatterns.NAME_MESSAGE)
    private String emergencyContactName;

    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.PHONE_REGEXP_OPTIONAL, message = "El teléfono de emergencia debe tener 10 dígitos")
    private String emergencyContactPhone;

    @jakarta.validation.constraints.AssertTrue(message = "La fecha de nacimiento no es realista (más de 120 años)")
    public boolean isBirthDateRealistic() {
        return birthDate == null || birthDate.isAfter(java.time.LocalDate.now().minusYears(120));
    }

    @jakarta.validation.constraints.AssertTrue(message = "Si capturas contacto de emergencia, indica su nombre y su teléfono")
    public boolean isEmergencyContactComplete() {
        boolean name = emergencyContactName != null && !emergencyContactName.isBlank();
        boolean phone = emergencyContactPhone != null && !emergencyContactPhone.isBlank();
        return name == phone;
    }
}
