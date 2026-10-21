package com.hospital.citas.security;

import com.hospital.citas.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * Bitácora automática: cada petición que toca datos sensibles (expediente, recetas,
 * comprobantes, exportaciones, ARCO, reembolsos) y termina bien queda registrada con quién,
 * qué, sobre cuál registro y desde qué IP. Se declara aquí, por ruta, para que ningún
 * controller "olvide" registrar (mismo enfoque que RateLimitInterceptor).
 */
@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {

    private record Rule(String method, String pattern, String action, String entityType) {
    }

    // Las rutas fijas van antes que las que llevan {id}: aplica la PRIMERA regla que coincide.
    private static final List<Rule> RULES = List.of(
            new Rule("GET", "/api/reception/patients/export.xlsx", "EXPORT_PATIENTS", null),
            new Rule("GET", "/api/reception/patients/template.xlsx", null, null),
            new Rule("GET", "/api/reception/patients/{id}", "VIEW_PATIENT", "PATIENT"),
            new Rule("PUT", "/api/reception/patients/{id}/profile", "UPDATE_PATIENT_PROFILE", "PATIENT"),
            new Rule("PUT", "/api/reception/patients/{id}/medical-info", "UPDATE_MEDICAL_INFO", "PATIENT"),
            new Rule("PUT", "/api/doctor/patients/{id}/medical-info", "UPDATE_MEDICAL_INFO", "PATIENT"),
            new Rule("GET", "/api/reception/patients/{id}/prescriptions", "VIEW_PRESCRIPTIONS", "PATIENT"),
            new Rule("GET", "/api/doctor/appointments/{id}/prescriptions", "VIEW_PRESCRIPTIONS", "APPOINTMENT"),
            new Rule("GET", "/api/reception/prescriptions/{id}/pdf", "DOWNLOAD_PRESCRIPTION", "PRESCRIPTION"),
            new Rule("GET", "/api/doctor/prescriptions/{id}/pdf", "DOWNLOAD_PRESCRIPTION", "PRESCRIPTION"),
            new Rule("POST", "/api/doctor/prescriptions", "CREATE_PRESCRIPTION", "PRESCRIPTION"),
            new Rule("POST", "/api/doctor/prescriptions/{id}/void", "VOID_PRESCRIPTION", "PRESCRIPTION"),
            new Rule("POST", "/api/doctor/prescriptions/{id}/send-email", "SEND_PRESCRIPTION", "PRESCRIPTION"),
            new Rule("GET", "/api/reception/appointments/{id}/receipt", "DOWNLOAD_RECEIPT", "APPOINTMENT"),
            new Rule("GET", "/api/doctor/appointments/{id}/receipt", "DOWNLOAD_RECEIPT", "APPOINTMENT"),
            new Rule("POST", "/api/reception/patients/{id}/merge-into/{targetId}", "MERGE_PATIENTS", "PATIENT"),
            new Rule("POST", "/api/reception/patients/import", "IMPORT_PATIENTS", null),
            new Rule("POST", "/api/admin/payments/{id}/refund", "REFUND_PAYMENT", "PAYMENT"),
            new Rule("GET", "/api/reports/export.xlsx", "REPORT_EXPORT", null),
            new Rule("PUT", "/api/admin/permissions/roles/{id}", "PERMISSIONS_ROLE", null),
            new Rule("PUT", "/api/admin/permissions/users/{id}", "PERMISSIONS_USER", "USER"),
            new Rule("GET", "/api/admin/arco-requests/{id}/documents/{documentId}", "ARCO_DOCUMENT_VIEW", "ARCO"),
            new Rule("POST", "/api/admin/arco-requests/{id}/verify-identity", "ARCO_IDENTITY", "ARCO"),
            new Rule("POST", "/api/admin/arco-requests/{id}/respond", "ARCO_RESPOND", "ARCO"),
            new Rule("POST", "/api/admin/arco-requests/{id}/execute", "ARCO_EXECUTE", "ARCO"),
            new Rule("POST", "/api/privacy/me/export", "PRIVACY_SELF_EXPORT", null));

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final AuditService auditService;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (ex != null || response.getStatus() >= 400) {
            return;
        }
        String path = request.getRequestURI();
        for (Rule rule : RULES) {
            if (rule.method().equals(request.getMethod()) && matcher.match(rule.pattern(), path)) {
                Map<String, String> vars = matcher.extractUriTemplateVariables(rule.pattern(), path);
                Long id = null;
                try {
                    id = vars.containsKey("id") ? Long.valueOf(vars.get("id")) : null;
                } catch (NumberFormatException ignored) {
                    // "export.xlsx" y similares no tienen id.
                }
                if (rule.action() == null) {
                    return;
                }
                auditService.record(SecurityUtils.getCurrentUserOrNull(), rule.action(), rule.entityType(), id, path,
                        request.getRemoteAddr());
                return;
            }
        }
    }

}
