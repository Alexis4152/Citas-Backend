package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class HospitalConfigResponse {
    /** Hospital dueño de esta configuración y su slug (el de su link /c/<slug>). */
    private Long hospitalId;
    private String slug;
    private String name;
    private String logoUrl;
    private String primaryColor;
    private String description;
    private String contactPhone;
    private String contactEmail;
}
