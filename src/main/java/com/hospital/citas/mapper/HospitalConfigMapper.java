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
                .legalName(config.getLegalName())
                .rfc(config.getRfc())
                .addressLine(config.getAddressLine())
                .neighborhood(config.getNeighborhood())
                .city(config.getCity())
                .state(config.getState())
                .postalCode(config.getPostalCode())
                .website(config.getWebsite())
                .whatsapp(config.getWhatsapp())
                .openingHours(config.getOpeningHours())
                .autoNoShowEnabled(config.getAutoNoShowEnabled())
                .noShowGraceMinutes(config.getNoShowGraceMinutes())
                .privacyContactName(config.getPrivacyContactName())
                .privacyContactEmail(config.getPrivacyContactEmail())
                .privacyNoticeVersion(config.getPrivacyNoticeVersion())
                .privacyNoticeUpdatedAt(config.getPrivacyNoticeUpdatedAt())
                .clinicalRetentionYears(config.getClinicalRetentionYears())
                .fiscalRetentionYears(config.getFiscalRetentionYears())
                .build();
    }
}
