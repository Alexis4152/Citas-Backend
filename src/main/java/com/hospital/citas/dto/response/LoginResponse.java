package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LoginResponse {
    private String token;
    private UserResponse user;
    private Boolean mustChangePassword;
    /** Ya había un expediente con ese correo: se mandó un enlace para activar la cuenta y ligarlo. */
    private Boolean pendingActivation;
}
