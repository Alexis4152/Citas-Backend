package com.hospital.citas.service.impl;

import com.hospital.citas.dto.request.EmailConfigRequest;
import com.hospital.citas.dto.response.EmailConfigResponse;
import com.hospital.citas.entity.EmailConfig;
import com.hospital.citas.repository.EmailConfigRepository;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.EmailConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class EmailConfigServiceImpl implements EmailConfigService {

    private final EmailConfigRepository emailConfigRepository;

    // Igual que HospitalConfigServiceImpl.cachedConfig: esta config se lee una vez por CADA
    // correo enviado (EmailServiceImpl consulta enabled/host/usuario/password en cada envío)
    // pero casi nunca se escribe -- cachear evita un SELECT completo por correo.
    private volatile EmailConfig cachedConfig;

    @Override
    public EmailConfigResponse get() {
        return toResponse(getEntity());
    }

    @Override
    @Transactional
    public EmailConfigResponse update(EmailConfigRequest request) {
        EmailConfig config = getEntity();
        config.setEnabled(request.isEnabled());
        config.setSmtpHost(request.getSmtpHost());
        config.setSmtpPort(request.getSmtpPort());
        config.setSmtpUsername(request.getSmtpUsername());
        config.setFromAddress(request.getFromAddress());
        // Campo write-only: un valor vacío significa "no cambiar", nunca "borrar la guardada".
        if (request.getSmtpPassword() != null && !request.getSmtpPassword().isBlank()) {
            config.setSmtpPassword(request.getSmtpPassword());
        }
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(SecurityUtils.getCurrentUserOrNull());
        EmailConfig saved = emailConfigRepository.save(config);
        cachedConfig = saved;
        return toResponse(saved);
    }

    @Override
    public EmailConfig getEntity() {
        EmailConfig cached = cachedConfig;
        if (cached != null) {
            return cached;
        }
        EmailConfig loaded = emailConfigRepository.findAll().stream().findFirst()
                .orElseGet(() -> emailConfigRepository.save(EmailConfig.builder()
                        .enabled(false)
                        .smtpHost("smtp.gmail.com")
                        .smtpPort(587)
                        .updatedAt(LocalDateTime.now())
                        .build()));
        cachedConfig = loaded;
        return loaded;
    }

    private EmailConfigResponse toResponse(EmailConfig config) {
        return EmailConfigResponse.builder()
                .enabled(Boolean.TRUE.equals(config.getEnabled()))
                .smtpHost(config.getSmtpHost())
                .smtpPort(config.getSmtpPort())
                .smtpUsername(config.getSmtpUsername())
                .fromAddress(config.getFromAddress())
                .passwordConfigured(config.getSmtpPassword() != null && !config.getSmtpPassword().isBlank())
                .build();
    }
}
