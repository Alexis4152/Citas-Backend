package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** Hospital visto por el SUPER_ADMIN. Nunca incluye la llave privada de OpenPay. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class HospitalResponse {
    private Long id;
    private String name;
    private String slug;
    /** Enlace que el hospital le pasa a sus pacientes. */
    private String publicUrl;
    private Boolean isActive;
    private boolean openpayConfigured;
    private String openpayMerchantId;
    private String openpayPublicKey;
    private boolean openpayPrivateKeyConfigured;
    private Boolean openpayProduction;
    private long doctors;
    private long patients;
    private long appointments;
    private List<UserResponse> admins;
    private LocalDateTime createdAt;
    /** Solo en la respuesta del alta (hospital o administrador nuevo): la contraseña temporal. */
    private String temporaryPassword;
}
