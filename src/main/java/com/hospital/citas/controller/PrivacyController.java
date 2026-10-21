package com.hospital.citas.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.ArcoRequestCreate;
import com.hospital.citas.dto.request.PatientProfileRequest;
import com.hospital.citas.dto.response.ArcoRequestResponse;
import com.hospital.citas.entity.HospitalConfig;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.entity.PrivacyNoticeVersion;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.ArcoRequestType;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.mapper.PatientMapper;
import com.hospital.citas.repository.PatientRepository;
import com.hospital.citas.repository.PrivacyNoticeVersionRepository;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Centro de privacidad (como el de Mercado Libre):
 * <ul>
 *   <li>{@code /api/privacy/me}: el paciente ve y corrige sus datos, ajusta para qué se usan,
 *   descarga su reporte de datos y pide eliminar su cuenta.</li>
 *   <li>{@code /api/admin/patients/{id}/privacy}: el admin ve consentimientos, notas aclaratorias
 *   y, con motivo registrado, los datos en resguardo de un paciente bloqueado.</li>
 *   <li>{@code /api/public/privacy-notice}: versiones del aviso de privacidad.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class PrivacyController {

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;
    private final PatientService patientService;
    private final PrivacyService privacyService;
    private final ConsentService consentService;
    private final ArcoRequestService arcoRequestService;
    private final PasswordEncoder passwordEncoder;
    private final PrivacyNoticeVersionRepository noticeVersionRepository;
    private final HospitalConfigService hospitalConfigService;
    private final ObjectMapper objectMapper;

    public record Preferences(Boolean reminders, Boolean news) {
    }

    public record PasswordBody(String password, String reason) {
    }

    public record ReasonBody(String reason) {
    }

    public record NoteBody(String note) {
    }

    // ── Paciente ────────────────────────────────────────────────

    @GetMapping("/api/privacy/me")
    public ApiResponse<Map<String, Object>> me() {
        User user = requireUser();
        Patient patient = patientRepository.findByUser_Id(user.getId()).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient", patient == null ? null : patientMapper.toResponse(patient));
        out.put("consents", consentService.history(patient, user));
        out.put("requests", arcoRequestService.listMine());
        out.put("noticeVersion", hospitalConfigService.getEntity().getPrivacyNoticeVersion());
        return ApiResponse.ok(out);
    }

    @PutMapping("/api/privacy/me/preferences")
    public ApiResponse<List<String>> preferences(@RequestBody Preferences body) {
        Patient patient = ownPatient();
        return ApiResponse.ok(privacyService.applyPreferences(patient, body.reminders(), body.news(), "PRIVACY_CENTER"), "Preferencias guardadas");
    }

    /** Rectificación directa de sus datos personales (el correo de acceso no se cambia aquí). */
    @PutMapping("/api/privacy/me/profile")
    public ApiResponse<Object> profile(@Valid @RequestBody PatientProfileRequest body) {
        Patient patient = ownPatient();
        body.setEmail(patient.getEmail());
        return ApiResponse.ok(patientService.updateProfile(patient.getId(), body), "Datos actualizados");
    }

    /** Descarga inmediata de su reporte de datos (con la contraseña, como confirmación de identidad). */
    @PostMapping("/api/privacy/me/export")
    public ResponseEntity<byte[]> export(@RequestBody PasswordBody body) {
        User user = requireUser();
        checkPassword(user, body.password());
        Patient patient = patientRepository.findByUser_Id(user.getId()).orElse(null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mis-datos-personales.pdf\"")
                .header("Cache-Control", "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .body(privacyService.dataReport(patient, user, null));
    }

    /** "Eliminar mi cuenta": se registra como solicitud de Cancelación (con plazos y constancia). */
    @PostMapping("/api/privacy/me/delete-account")
    public ResponseEntity<ApiResponse<ArcoRequestResponse>> deleteAccount(@RequestBody PasswordBody body) {
        ArcoRequestCreate req = new ArcoRequestCreate();
        req.setType(ArcoRequestType.CANCELLATION);
        req.setPassword(body.password());
        req.setAcceptPrivacyNotice(true);
        req.setDetails(body.reason() == null || body.reason().isBlank() ? "Eliminar mi cuenta y mis datos." : body.reason().trim());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(arcoRequestService.createOwn(req),
                "Recibimos tu solicitud para eliminar tu cuenta. Te avisaremos por correo cuando quede hecha."));
    }

    // ── Administrador: privacidad de un paciente ────────────────

    @GetMapping("/api/admin/patients/{id}/privacy")
    public ApiResponse<Map<String, Object>> adminPrivacy(@PathVariable Long id) {
        Patient patient = patientRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Paciente no encontrado"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("privacyStatus", patient.getPrivacyStatus());
        out.put("retainUntil", patient.getRetainUntil());
        out.put("blockedAt", patient.getBlockedAt());
        out.put("notifyReminders", patient.getNotifyReminders());
        out.put("notifyNews", patient.getNotifyNews());
        out.put("emailMoved", patient.getEmailMoved());
        out.put("consents", consentService.history(patient, patient.getUser()));
        out.put("clarifications", privacyService.clarifications(id));
        return ApiResponse.ok(out);
    }

    @PostMapping("/api/admin/patients/{id}/vault")
    public ApiResponse<Map<String, Object>> vault(@PathVariable Long id, @RequestBody ReasonBody body) {
        return ApiResponse.ok(privacyService.openVault(id, body.reason(), requireUser()), "Consulta registrada en la bitácora");
    }

    @PostMapping("/api/admin/patients/{id}/clarifications")
    public ApiResponse<Object> addClarification(@PathVariable Long id, @RequestBody NoteBody body) {
        Patient patient = patientRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Paciente no encontrado"));
        privacyService.addClarification(patient, null, body.note(), requireUser());
        return ApiResponse.ok(privacyService.clarifications(id), "Nota aclaratoria agregada");
    }

    /** Las notas aclaratorias son parte del expediente: las ve el personal que abre expedientes. */
    @GetMapping("/api/reception/patients/{id}/clarifications")
    public ApiResponse<List<Map<String, Object>>> clarifications(@PathVariable Long id) {
        patientService.requireAccessible(id);
        return ApiResponse.ok(privacyService.clarifications(id));
    }

    // ── Público: versiones del aviso de privacidad ──────────────

    @GetMapping("/api/public/privacy-notice/versions")
    public ApiResponse<List<Map<String, Object>>> versions() {
        HospitalConfig h = hospitalConfigService.getEntity();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of("version", h.getPrivacyNoticeVersion(), "publishedAt",
                h.getPrivacyNoticeUpdatedAt() != null ? h.getPrivacyNoticeUpdatedAt() : h.getUpdatedAt(), "current", true));
        for (PrivacyNoticeVersion v : noticeVersionRepository.findAllByOrderByVersionDesc()) {
            if (!v.getVersion().equals(h.getPrivacyNoticeVersion())) {
                out.add(Map.of("version", v.getVersion(), "publishedAt", v.getPublishedAt(), "current", false));
            }
        }
        return ApiResponse.ok(out);
    }

    @GetMapping("/api/public/privacy-notice/versions/{version}")
    public ApiResponse<Map<String, Object>> version(@PathVariable Integer version) {
        PrivacyNoticeVersion v = noticeVersionRepository.findByVersion(version)
                .orElseThrow(() -> new ResourceNotFoundException("Esa versión del aviso no existe"));
        try {
            Map<String, Object> snapshot = objectMapper.readValue(v.getSnapshot(), new TypeReference<LinkedHashMap<String, Object>>() {});
            snapshot.put("version", v.getVersion());
            snapshot.put("publishedAt", v.getPublishedAt());
            return ApiResponse.ok(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Ayudas ──────────────────────────────────────────────────

    private Patient ownPatient() {
        return patientRepository.findByUser_Id(requireUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Tu cuenta no tiene un expediente de paciente"));
    }

    private void checkPassword(User user, String password) {
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException("La contraseña no es correcta");
        }
    }

    private User requireUser() {
        User user = SecurityUtils.getCurrentUserOrNull();
        if (user == null) throw new ResourceNotFoundException("No hay sesión activa");
        return user;
    }
}
