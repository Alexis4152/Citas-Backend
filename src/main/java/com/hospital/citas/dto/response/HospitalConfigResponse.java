package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class HospitalConfigResponse {
    private String name;
    private String logoUrl;
    private String primaryColor;
    private String description;
    private String contactPhone;
    private String contactEmail;
}
