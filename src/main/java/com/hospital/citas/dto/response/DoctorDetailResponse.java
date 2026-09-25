package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DoctorDetailResponse {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private SpecialtyResponse specialty;
    private String licenseNumber;
    private String bio;
    private String photoUrl;
    private java.math.BigDecimal consultationPrice;
    private String prescriptionTemplate;
    private Integer defaultSlotMinutes;
    private List<BranchResponse> branches;
    private Boolean isActive;

    /** Solo presente en la respuesta del alta (POST): la contraseña temporal generada por el
     * servidor, para que el ADMIN se la comunique al doctor. Nunca se guarda ni se vuelve a
     * exponer en un GET posterior. */
    private String temporaryPassword;
}
