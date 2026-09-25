package com.hospital.citas.service.impl;

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

    // Config de un solo renglón, leída MUCHAS veces (cada correo, cada PDF de comprobante)
    // y escrita casi nunca (solo desde /admin/configuracion) -- sin esto, cada envío de
    // correo o generación de PDF hacía su propio SELECT completo a esta tabla. `volatile`
    // alcanza porque solo hay una instancia de este bean y la única invalidación es
    // reemplazar la referencia completa tras guardar (ver update()/updateLogo()).
    private volatile HospitalConfig cachedConfig;

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
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        HospitalConfig saved = hospitalConfigRepository.save(config);
        cachedConfig = saved;
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
        cachedConfig = saved;
        return hospitalConfigMapper.toResponse(saved);
    }

    @Override
    public HospitalConfig getEntity() {
        HospitalConfig cached = cachedConfig;
        if (cached != null) {
            return cached;
        }
        HospitalConfig loaded = hospitalConfigRepository.findAll().stream().findFirst()
                .orElseGet(() -> hospitalConfigRepository.save(HospitalConfig.builder()
                        .name("Hospital Central")
                        .primaryColor("#0F766E")
                        .updatedAt(LocalDateTime.now())
                        .build()));
        cachedConfig = loaded;
        return loaded;
    }
}
