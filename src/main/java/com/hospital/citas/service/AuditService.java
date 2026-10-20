package com.hospital.citas.service;

import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.entity.AuditLog;
import com.hospital.citas.entity.User;
import com.hospital.citas.repository.AuditLogRepository;
import com.hospital.citas.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Registra y consulta la bitácora de acceso a datos sensibles (ver {@link AuditLog}). */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** Etiquetas para mostrar en el panel. */
    public static final Map<String, String> ACTION_LABELS = new LinkedHashMap<>();

    static {
        ACTION_LABELS.put("VIEW_PATIENT", "Consultó el expediente");
        ACTION_LABELS.put("UPDATE_PATIENT_PROFILE", "Cambió los datos personales");
        ACTION_LABELS.put("UPDATE_MEDICAL_INFO", "Cambió la información médica");
        ACTION_LABELS.put("VIEW_PRESCRIPTIONS", "Consultó recetas");
        ACTION_LABELS.put("DOWNLOAD_PRESCRIPTION", "Descargó una receta");
        ACTION_LABELS.put("CREATE_PRESCRIPTION", "Emitió una receta");
        ACTION_LABELS.put("VOID_PRESCRIPTION", "Anuló una receta");
        ACTION_LABELS.put("SEND_PRESCRIPTION", "Envió una receta por correo");
        ACTION_LABELS.put("DOWNLOAD_RECEIPT", "Descargó un comprobante");
        ACTION_LABELS.put("MERGE_PATIENTS", "Fusionó pacientes");
        ACTION_LABELS.put("EXPORT_PATIENTS", "Exportó la lista de pacientes");
        ACTION_LABELS.put("IMPORT_PATIENTS", "Importó pacientes");
        ACTION_LABELS.put("REFUND_PAYMENT", "Reembolsó un cobro");
        ACTION_LABELS.put("REPORT_EXPORT", "Exportó un reporte");
        ACTION_LABELS.put("PERMISSIONS_ROLE", "Cambió los permisos de un rol");
        ACTION_LABELS.put("PERMISSIONS_USER", "Cambió los permisos de una persona");
        ACTION_LABELS.put("ARCO_EXPORT", "Descargó datos personales (ARCO)");
        ACTION_LABELS.put("ARCO_RESOLVE", "Resolvió una solicitud ARCO");
    }

    private final AuditLogRepository repository;

    /** Transacción propia: se guarda aunque la consulta auditada haya sido de solo lectura. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User user, String action, String entityType, Long entityId, String path, String ip) {
        if (TenantContext.hospitalIdOrNull() == null) {
            return;
        }
        try {
            repository.save(AuditLog.builder()
                    .userId(user != null ? user.getId() : null)
                    .userName(user != null ? user.getFirstName() + " " + user.getLastName() : "Sistema")
                    .userRole(user != null ? user.getRole().getName().name() : null)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .path(path != null && path.length() > 255 ? path.substring(0, 255) : path)
                    .clientIp(ip)
                    .build());
        } catch (Exception e) {
            // La bitácora nunca debe tumbar la operación que se está registrando.
            log.error("No se pudo registrar en la bitácora la acción {} sobre {} {}", action, entityType, entityId, e);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> search(String action, String entityType, Long entityId, String userQuery,
                                                    LocalDate from, LocalDate to, int page, int size) {
        LocalDateTime start = (from != null ? from : LocalDate.now().minusDays(30)).atStartOfDay();
        LocalDateTime end = (to != null ? to : LocalDate.now()).plusDays(1).atStartOfDay();
        var result = repository.findAll(
                AuditLogRepository.filter(blankToNull(action), blankToNull(entityType), entityId, blankToNull(userQuery), start, end),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result.map(l -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", l.getId());
            row.put("createdAt", l.getCreatedAt());
            row.put("userName", l.getUserName());
            row.put("userRole", l.getUserRole());
            row.put("action", l.getAction());
            row.put("actionLabel", ACTION_LABELS.getOrDefault(l.getAction(), l.getAction()));
            row.put("entityType", l.getEntityType());
            row.put("entityId", l.getEntityId());
            row.put("clientIp", l.getClientIp());
            return row;
        }));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
