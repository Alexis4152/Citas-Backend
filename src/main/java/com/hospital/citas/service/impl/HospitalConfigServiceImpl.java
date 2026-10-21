package com.hospital.citas.service.impl;

import java.util.concurrent.ConcurrentHashMap;

import java.util.LinkedHashMap;
import java.util.Map;

import com.hospital.citas.tenant.HospitalDirectory;

import com.hospital.citas.tenant.TenantContext;

import com.hospital.citas.dto.request.HospitalConfigRequest;
import com.hospital.citas.dto.response.HospitalConfigResponse;
import com.hospital.citas.entity.HospitalConfig;
import com.hospital.citas.mapper.HospitalConfigMapper;
import com.hospital.citas.repository.HospitalConfigRepository;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.FileStorageService;
import com.hospital.citas.service.HospitalConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class HospitalConfigServiceImpl implements HospitalConfigService {

    private static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;
    // Solo formatos que PDFBox puede incrustar en el comprobante de cita (patrón 09): WEBP
    // y SVG se veían bien en la web pero tronaban la generación del PDF (PDFBox/ImageIO no
    // los soporta) -- ver AppointmentReceiptServiceImpl.loadLogo.
    private static final Set<String> ALLOWED_LOGO_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    private final HospitalConfigRepository hospitalConfigRepository;
    private final HospitalConfigMapper hospitalConfigMapper;
    private final FileStorageService fileStorageService;
    private final HospitalDirectory hospitalDirectory;
    private final com.hospital.citas.repository.PrivacyNoticeVersionRepository noticeVersionRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // Un renglón por hospital, leído MUCHAS veces (cada correo, cada PDF de comprobante) y
    // escrito casi nunca (solo desde /admin/configuracion) -- sin esto, cada envío de correo
    // o generación de PDF hacía su propio SELECT a esta tabla. La llave es el hospital: cada
    // uno ve solo su nombre, logo y color.
    private final Map<Long, HospitalConfig> cache = new ConcurrentHashMap<>();

    @Override
    public HospitalConfigResponse get() {
        return hospitalConfigMapper.toResponse(getEntity());
    }

    @Override
    @Transactional
    public HospitalConfigResponse update(HospitalConfigRequest request) {
        HospitalConfig config = getEntity();
        config.setName(request.getName());
        config.setLogoUrl(request.getLogoUrl());
        config.setPrimaryColor(request.getPrimaryColor());
        config.setDescription(request.getDescription());
        config.setContactPhone(request.getContactPhone());
        config.setContactEmail(request.getContactEmail());
        config.setLegalName(blankToNull(request.getLegalName()));
        config.setRfc(upperOrNull(request.getRfc()));
        config.setAddressLine(blankToNull(request.getAddressLine()));
        config.setNeighborhood(blankToNull(request.getNeighborhood()));
        config.setCity(blankToNull(request.getCity()));
        config.setState(blankToNull(request.getState()));
        config.setPostalCode(blankToNull(request.getPostalCode()));
        config.setWebsite(blankToNull(request.getWebsite()));
        config.setWhatsapp(blankToNull(request.getWhatsapp()));
        config.setOpeningHours(blankToNull(request.getOpeningHours()));
        if (request.getAutoNoShowEnabled() != null) config.setAutoNoShowEnabled(request.getAutoNoShowEnabled());
        if (request.getNoShowGraceMinutes() != null) config.setNoShowGraceMinutes(request.getNoShowGraceMinutes());
        config.setPrivacyContactName(blankToNull(request.getPrivacyContactName()));
        config.setPrivacyContactEmail(blankToNull(request.getPrivacyContactEmail()));
        if (request.getClinicalRetentionYears() != null) config.setClinicalRetentionYears(request.getClinicalRetentionYears());
        if (request.getFiscalRetentionYears() != null) config.setFiscalRetentionYears(request.getFiscalRetentionYears());
        if (Boolean.TRUE.equals(request.getPublishPrivacyNotice())) {
            // La versión vigente se guarda antes de publicar la nueva, para poder consultarla después.
            saveNoticeSnapshot(config);
            config.setPrivacyNoticeVersion(config.getPrivacyNoticeVersion() + 1);
            config.setPrivacyNoticeUpdatedAt(LocalDateTime.now());
        }
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        HospitalConfig saved = hospitalConfigRepository.save(config);
        cache.put(saved.getHospitalId(), saved);
        return hospitalConfigMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public HospitalConfigResponse updateLogo(MultipartFile logo) {
        HospitalConfig config = getEntity();
        String relativePath = fileStorageService.store(logo, "hospital", MAX_LOGO_BYTES, ALLOWED_LOGO_EXTENSIONS);
        config.setLogoUrl(relativePath);
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        HospitalConfig saved = hospitalConfigRepository.save(config);
        cache.put(saved.getHospitalId(), saved);
        return hospitalConfigMapper.toResponse(saved);
    }

    @Override
    public HospitalConfig getEntity() {
        long hospitalId = TenantContext.requireHospitalId();
        HospitalConfig cached = cache.get(hospitalId);
        if (cached != null) {
            return cached;
        }
        // findAll() ya viene filtrado por el hospital actual (@TenantId): a lo más un renglón.
        HospitalConfig loaded = hospitalConfigRepository.findAll().stream().findFirst()
                .orElseGet(() -> hospitalConfigRepository.save(HospitalConfig.builder()
                        .name(hospitalDirectory.current().getName())
                        .primaryColor("#0F766E")
                        .updatedAt(LocalDateTime.now())
                        .build()));
        cache.put(hospitalId, loaded);
        return loaded;
    }

    private void saveNoticeSnapshot(HospitalConfig c) {
        if (noticeVersionRepository.findByVersion(c.getPrivacyNoticeVersion()).isPresent()) return;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", c.getName());
        s.put("legalName", c.getLegalName());
        s.put("rfc", c.getRfc());
        s.put("addressLine", c.getAddressLine());
        s.put("neighborhood", c.getNeighborhood());
        s.put("city", c.getCity());
        s.put("state", c.getState());
        s.put("postalCode", c.getPostalCode());
        s.put("contactEmail", c.getContactEmail());
        s.put("contactPhone", c.getContactPhone());
        s.put("website", c.getWebsite());
        s.put("privacyContactName", c.getPrivacyContactName());
        s.put("privacyContactEmail", c.getPrivacyContactEmail());
        s.put("clinicalRetentionYears", c.getClinicalRetentionYears());
        s.put("fiscalRetentionYears", c.getFiscalRetentionYears());
        s.put("privacyNoticeUpdatedAt", c.getPrivacyNoticeUpdatedAt() == null ? null : c.getPrivacyNoticeUpdatedAt().toString());
        try {
            noticeVersionRepository.save(com.hospital.citas.entity.PrivacyNoticeVersion.builder()
                    .version(c.getPrivacyNoticeVersion())
                    .publishedAt(c.getPrivacyNoticeUpdatedAt() != null ? c.getPrivacyNoticeUpdatedAt() : c.getUpdatedAt())
                    .snapshot(objectMapper.writeValueAsString(s)).build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String upperOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
