package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BranchResponse {
    private Long id;
    private String name;
    private String address;
    private String city;
    private String phone;
    private LocalTime openTime;
    private LocalTime closeTime;
    private Boolean isActive;
}
