package com.hospital.citas.service;

import com.hospital.citas.entity.ConsentRecord;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.entity.User;
import com.hospital.citas.repository.ConsentRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

/**
 * Constancias de consentimiento: al agendar, registrarse o en recepción se guarda que la
 * persona aceptó el aviso de privacidad (y, aparte, el tratamiento de sus datos de salud), con
 * la versión del aviso, la fecha, el medio y la IP. Retirar un consentimiento es otro renglón.
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ConsentRecordRepository consentRecordRepository;
    private final HospitalConfigService hospitalConfigService;

    /** Aceptación del aviso + consentimiento expreso para datos de salud. */
    @Transactional
    public void recordAcceptance(Patient patient, User user, String channel) {
        record(patient, user, ConsentRecord.NOTICE, true, channel);
        record(patient, user, ConsentRecord.SENSITIVE, true, channel);
    }

    @Transactional
    public void record(Patient patient, User user, String scope, boolean granted, String channel) {
        consentRecordRepository.save(ConsentRecord.builder()
                .patient(patient)
                .user(user)
                .noticeVersion(hospitalConfigService.getEntity().getPrivacyNoticeVersion())
                .scope(scope)
                .granted(granted)
                .channel(channel)
                .ip(clientIp())
                .build());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(Patient patient, User user) {
        return consentRecordRepository.history(patient == null ? -1L : patient.getId(), user == null ? -1L : user.getId())
                .stream().map(c -> Map.<String, Object>of(
                        "scope", c.getScope(), "granted", c.getGranted(), "channel", c.getChannel(),
                        "noticeVersion", c.getNoticeVersion(), "at", c.getCreatedAt()))
                .toList();
    }

    static String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        HttpServletRequest request = attrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return ip == null ? null : ip.length() > 64 ? ip.substring(0, 64) : ip;
    }
}
