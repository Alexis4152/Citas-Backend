package com.hospital.citas.security;

import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permisos efectivos de una persona = permisos de su rol EN SU HOSPITAL (personalizados o los
 * de fábrica) + excepciones de esa persona (dar o quitar). El ADMIN siempre tiene todos; el
 * paciente ninguno (sus pantallas son otras). Se guarda en caché y se invalida al cambiar.
 * <p>
 * JDBC directo con el hospital explícito: tablas sencillas de configuración, sin entidades.
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;

    private final Map<String, Set<Permission>> roleCache = new ConcurrentHashMap<>();
    private final Map<Long, Map<Permission, Boolean>> overrideCache = new ConcurrentHashMap<>();

    public Set<Permission> effective(User user) {
        if (user == null || user.getRole() == null) return EnumSet.noneOf(Permission.class);
        RoleName role = user.getRole().getName();
        if (role == RoleName.ADMIN) return EnumSet.allOf(Permission.class);
        if (!Permission.configurable(role) || user.getHospitalId() == null) return EnumSet.noneOf(Permission.class);
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        result.addAll(forRole(user.getHospitalId(), role));
        overridesOf(user.getHospitalId(), user.getId()).forEach((p, granted) -> {
            if (granted) result.add(p);
            else result.remove(p);
        });
        return result;
    }

    public boolean has(User user, Permission permission) {
        return effective(user).contains(permission);
    }

    /** Para validaciones dentro de un servicio (ej. sobrecupo, que va en el cuerpo de la petición). */
    public void require(User user, Permission permission) {
        if (user != null && Permission.configurable(user.getRole().getName()) && !has(user, permission)) {
            throw new PermissionDeniedException(permission);
        }
    }

    public Set<Permission> forRole(long hospitalId, RoleName role) {
        return roleCache.computeIfAbsent(hospitalId + ":" + role, k -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT permission, granted FROM role_permissions WHERE hospital_id = ? AND role = ?", hospitalId, role.name());
            EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
            set.addAll(Permission.defaultsFor(role));
            for (Map<String, Object> row : rows) {
                Permission p = parse((String) row.get("permission"));
                if (p == null) continue;
                if (Boolean.TRUE.equals(row.get("granted"))) set.add(p);
                else set.remove(p);
            }
            return set;
        });
    }

    public Map<Permission, Boolean> overridesOf(long hospitalId, long userId) {
        return overrideCache.computeIfAbsent(userId, k -> {
            Map<Permission, Boolean> map = new EnumMap<>(Permission.class);
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT permission, effect FROM user_permission_overrides WHERE hospital_id = ? AND user_id = ?", hospitalId, userId)) {
                Permission p = parse((String) row.get("permission"));
                if (p != null) map.put(p, "GRANT".equals(row.get("effect")));
            }
            return map;
        });
    }

    // ── Administración (ADMIN del hospital) ─────────────────────

    /** Guarda el conjunto completo de permisos de un rol para el hospital actual. */
    @Transactional
    public void saveRole(RoleName role, Set<Permission> granted) {
        if (!Permission.configurable(role)) throw new BusinessException("Ese rol no se puede personalizar");
        long hospitalId = TenantContext.requireHospitalId();
        jdbc.update("DELETE FROM role_permissions WHERE hospital_id = ? AND role = ?", hospitalId, role.name());
        for (Permission p : Permission.values()) {
            jdbc.update("INSERT INTO role_permissions (hospital_id, role, permission, granted) VALUES (?, ?, ?, ?)",
                    hospitalId, role.name(), p.name(), granted.contains(p));
        }
        roleCache.remove(hospitalId + ":" + role);
    }

    @Transactional
    public void resetRole(RoleName role) {
        long hospitalId = TenantContext.requireHospitalId();
        jdbc.update("DELETE FROM role_permissions WHERE hospital_id = ? AND role = ?", hospitalId, role.name());
        roleCache.remove(hospitalId + ":" + role);
    }

    /** Excepciones de una persona: GRANT (dar), DENY (quitar); lo que no venga = como su rol. */
    @Transactional
    public void saveOverrides(long userId, Map<Permission, Boolean> overrides) {
        long hospitalId = TenantContext.requireHospitalId();
        User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException("Usuario no encontrado"));
        if (!Permission.configurable(user.getRole().getName())) {
            throw new BusinessException("Solo se pueden personalizar permisos de recepción y doctores");
        }
        jdbc.update("DELETE FROM user_permission_overrides WHERE hospital_id = ? AND user_id = ?", hospitalId, userId);
        overrides.forEach((p, grant) -> jdbc.update(
                "INSERT INTO user_permission_overrides (hospital_id, user_id, permission, effect) VALUES (?, ?, ?, ?)",
                hospitalId, userId, p.name(), grant ? "GRANT" : "DENY"));
        overrideCache.remove(userId);
    }

    private static Permission parse(String code) {
        try {
            return Permission.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** No tiene el permiso: 403 con un mensaje que dice qué le falta. */
    public static class PermissionDeniedException extends RuntimeException {
        public PermissionDeniedException(Permission permission) {
            super("No tienes permiso para: " + permission.label().toLowerCase(Locale.ROOT)
                    + ". Pídeselo al administrador del hospital.");
        }
    }
}
