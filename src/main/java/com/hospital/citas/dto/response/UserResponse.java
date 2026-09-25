package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserResponse {
    private Long id;
    private String email;
    private String firstName;
    private String lastName;
    private String phone;
    private String role;
    private Boolean isActive;
    private Boolean mustChangePassword;
    private LocalDateTime createdAt;

    /** Solo relevante para RECEPTIONIST: especialidades a las que queda restringido (ver
     * User#specialties). Vacía en todos los demás roles, y en un recepcionista general. */
    private List<SpecialtyResponse> specialties;

    /** Solo presente en la respuesta del alta (POST) de un recepcionista: la contraseña
     * temporal generada por el servidor. Nunca se guarda ni se vuelve a exponer. */
    private String temporaryPassword;
}
