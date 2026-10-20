package com.hospital.citas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.hospital.citas.security.Permission.*;

/**
 * Aplica los permisos configurables a recepción y doctores, por ruta (mismo enfoque declarativo
 * que RateLimitInterceptor y AuditInterceptor). La autorización por rol de SecurityConfig sigue
 * siendo la primera barrera; esto la afina según lo que cada hospital decidió. El ADMIN pasa
 * siempre; si una ruta no aparece aquí, decide solo el rol.
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private record Rule(String method, String pattern, Permission permission) {
    }

    // Las rutas fijas antes que las que llevan {id}: aplica la PRIMERA que coincide.
    private static final List<Rule> RULES = List.of(
            new Rule("POST", "/api/reception/appointments", APPOINTMENTS_CREATE),
            new Rule("POST", "/api/*/appointments/*/cancel", APPOINTMENTS_CANCEL),
            new Rule("PATCH", "/api/*/appointments/*/reschedule", APPOINTMENTS_RESCHEDULE),
            new Rule("PATCH", "/api/*/appointments/*/release-slot", APPOINTMENTS_RELEASE),
            new Rule("PATCH", "/api/*/appointments/*/arrived", APPOINTMENTS_STATUS),
            new Rule("PATCH", "/api/*/appointments/*/complete", APPOINTMENTS_STATUS),
            new Rule("PATCH", "/api/*/appointments/*/no-show", APPOINTMENTS_STATUS),
            new Rule("GET", "/api/reception/patients/template.xlsx", PATIENTS_IMPORT),
            new Rule("GET", "/api/reception/patients/export.xlsx", PATIENTS_EXPORT),
            new Rule("POST", "/api/reception/patients/import", PATIENTS_IMPORT),
            new Rule("POST", "/api/reception/patients/*/merge-into/*", PATIENTS_MERGE),
            new Rule("GET", "/api/reception/patients/*/duplicates", PATIENTS_MERGE),
            new Rule("PUT", "/api/*/patients/*/profile", PATIENTS_EDIT),
            new Rule("PUT", "/api/*/patients/*/medical-info", PATIENTS_EDIT),
            new Rule("GET", "/api/reception/patients/*/prescriptions", PRESCRIPTIONS_VIEW),
            new Rule("GET", "/api/reception/prescriptions/*/pdf", PRESCRIPTIONS_VIEW),
            new Rule("GET", "/api/reception/patients/*", PATIENTS_MODULE),
            new Rule("POST", "/api/reception/appointments/*/charge", PAYMENTS_CHARGE),
            new Rule("GET", "/api/reception/charge/**", PAYMENTS_CHARGE),
            new Rule("POST", "/api/reception/cash-cut/**", CASH_CUT),
            new Rule("GET", "/api/reception/cash-cut/**", CASH_CUT),
            new Rule("GET", "/api/reports/export.xlsx", REPORTS_EXPORT),
            new Rule("GET", "/api/reports/table/*/export", REPORTS_EXPORT),
            new Rule("GET", "/api/reports/**", REPORTS_VIEW));

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        User user = SecurityUtils.getCurrentUserOrNull();
        if (user == null || user.getRole() == null || !Permission.configurable(user.getRole().getName())) {
            return true;
        }
        String path = request.getRequestURI();
        for (Rule rule : RULES) {
            if (rule.method().equals(request.getMethod()) && matcher.match(rule.pattern(), path)) {
                if (permissionService.has(user, rule.permission())) {
                    return true;
                }
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                objectMapper.writeValue(response.getWriter(), ApiResponse.error(
                        new PermissionService.PermissionDeniedException(rule.permission()).getMessage()));
                return false;
            }
        }
        return true;
    }
}
