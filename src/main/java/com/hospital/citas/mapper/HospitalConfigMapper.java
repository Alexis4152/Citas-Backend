package com.hospital.citas.mapper;

import com.hospital.citas.dto.response.HospitalConfigResponse;
import com.hospital.citas.entity.HospitalConfig;
import org.springframework.stereotype.Component;

@Component
public class HospitalConfigMapper {

    public HospitalConfigResponse toResponse(HospitalConfig config) {
        return HospitalConfigResponse.builder()
                .name(config.getName())
                .logoUrl(config.getLogoUrl())
                .primaryColor(config.getPrimaryColor())
                .description(config.getDescription())
                .contactPhone(config.getContactPhone())
                .contactEmail(config.getContactEmail())
                .build();
    }
}
