package com.hospital.citas.mapper;

import lombok.RequiredArgsConstructor;

import com.hospital.citas.tenant.HospitalDirectory;

import com.hospital.citas.entity.Hospital;

import com.hospital.citas.dto.response.HospitalConfigResponse;
import com.hospital.citas.entity.HospitalConfig;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HospitalConfigMapper {

    private final HospitalDirectory hospitalDirectory;

    public HospitalConfigResponse toResponse(HospitalConfig config) {
        Hospital hospital = config.getHospitalId() == null ? null : hospitalDirectory.get(config.getHospitalId());
        return HospitalConfigResponse.builder()
                .hospitalId(config.getHospitalId())
                .slug(hospital == null ? null : hospital.getSlug())
                .name(config.getName())
                .logoUrl(config.getLogoUrl())
                .primaryColor(config.getPrimaryColor())
                .description(config.getDescription())
                .contactPhone(config.getContactPhone())
                .contactEmail(config.getContactEmail())
                .build();
    }
}
