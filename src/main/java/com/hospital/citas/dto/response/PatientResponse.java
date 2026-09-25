package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PatientResponse {
    private Long id;
    private String firstName;
    private String lastName;
    private String phone;
    private String email;

    /** true si este paciente tiene cuenta propia (puede iniciar sesión) -- ver Patient.user. */
    private Boolean hasAccount;

    /** Solo presente en la respuesta del alta (POST) cuando se pidió crear cuenta: la
     * contraseña temporal generada por el servidor. Nunca se guarda ni se vuelve a exponer
     * en un GET posterior. */
    private String temporaryPassword;

    /** "Ninguna" = el paciente respondió explícitamente que no tiene. Nulo = registro
     * anterior a este campo, nunca capturado. */
    private String allergies;
    private String bloodType;
    private String bloodTypeOther;

    /** Auditoría de quién capturó/modificó por última vez alergias/tipo de sangre -- nulo si
     * lo reportó un invitado sin cuenta identificada (ver Patient#medicalInfoUpdatedBy). */
    private String medicalInfoUpdatedByName;
    private LocalDateTime medicalInfoUpdatedAt;
}
