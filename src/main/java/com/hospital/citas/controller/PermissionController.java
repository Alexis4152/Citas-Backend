package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.security.Permission;
import com.hospital.citas.security.PermissionService;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Roles y permisos: el ADMIN del hospital ajusta qué puede hacer Recepción y Doctor, y da o
 * quita permisos a una persona. Cualquier usuario con sesión consulta los suyos (el menú y los
 * botones del panel se muestran según esto; el backend igual los valida en cada petición).
 */
@RestController
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;
    private final UserRepository userRepository;

    @GetMapping("/api/auth/permissions")
    public ApiResponse<List<String>> mine() {
        return ApiResponse.ok(permissionService.effective(SecurityUtils.getCurrentUserOrNull()).stream().map(Enum::name).toList());
    }

    @GetMapping("/api/admin/permissions")
    public ApiResponse<Map<String, Object>> matrix() {
        long hospitalId = TenantContext.requireHospitalId();
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (Permission p : Permission.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", p.name());
            item.put("module", p.module());
            item.put("label", p.label());
            item.put("defaultReceptionist", p.defaultFor(RoleName.RECEPTIONIST));
            item.put("defaultDoctor", p.defaultFor(RoleName.DOCTOR));
            catalog.add(item);
        }
        Map<String, Object> roles = new LinkedHashMap<>();
        for (RoleName role : List.of(RoleName.RECEPTIONIST, RoleName.DOCTOR)) {
            roles.put(role.name(), permissionService.forRole(hospitalId, role).stream().map(Enum::name).toList());
        }
        return ApiResponse.ok(Map.of("catalog", catalog, "roles", roles));
    }

    @PutMapping("/api/admin/permissions/roles/{role}")
    public ApiResponse<Void> saveRole(@PathVariable RoleName role, @RequestBody Map<String, List<String>> body) {
        permissionService.saveRole(role, parse(body.getOrDefault("permissions", List.of())));
        return ApiResponse.ok(null, "Permisos del rol guardados");
    }

    @PostMapping("/api/admin/permissions/roles/{role}/reset")
    public ApiResponse<Void> resetRole(@PathVariable RoleName role) {
        permissionService.resetRole(role);
        return ApiResponse.ok(null, "El rol volvió a sus permisos de fábrica");
    }

    /** Personal cuyos permisos se pueden ajustar (recepción y doctores activos del hospital). */
    @GetMapping("/api/admin/permissions/staff")
    public ApiResponse<List<Map<String, Object>>> staff() {
        long hospitalId = TenantContext.requireHospitalId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (RoleName role : List.of(RoleName.RECEPTIONIST, RoleName.DOCTOR)) {
            for (User u : userRepository.findByRole_NameAndIsActiveTrue(role)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", u.getId());
                row.put("firstName", u.getFirstName());
                row.put("lastName", u.getLastName());
                row.put("email", u.getEmail());
                row.put("role", role.name());
                row.put("exceptions", permissionService.overridesOf(hospitalId, u.getId()).size());
                out.add(row);
            }
        }
        out.sort(Comparator.comparing(r -> (r.get("firstName") + " " + r.get("lastName")).toLowerCase(Locale.ROOT)));
        return ApiResponse.ok(out);
    }

    @GetMapping("/api/admin/permissions/users/{userId}")
    public ApiResponse<Map<String, Object>> user(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException("Usuario no encontrado"));
        Map<String, Boolean> overrides = new LinkedHashMap<>();
        permissionService.overridesOf(TenantContext.requireHospitalId(), userId).forEach((p, g) -> overrides.put(p.name(), g));
        return ApiResponse.ok(Map.of(
                "role", user.getRole().getName().name(),
                "rolePermissions", permissionService.forRole(TenantContext.requireHospitalId(), user.getRole().getName())
                        .stream().map(Enum::name).toList(),
                "overrides", overrides,
                "effective", permissionService.effective(user).stream().map(Enum::name).toList()));
    }

    @PutMapping("/api/admin/permissions/users/{userId}")
    public ApiResponse<Void> saveUser(@PathVariable Long userId, @RequestBody Map<String, Map<String, Boolean>> body) {
        Map<Permission, Boolean> overrides = new EnumMap<>(Permission.class);
        body.getOrDefault("overrides", Map.of()).forEach((code, grant) -> {
            if (grant != null) overrides.put(parseOne(code), grant);
        });
        permissionService.saveOverrides(userId, overrides);
        return ApiResponse.ok(null, "Permisos de la persona guardados");
    }

    private static Set<Permission> parse(List<String> codes) {
        EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
        codes.forEach(c -> set.add(parseOne(c)));
        return set;
    }

    private static Permission parseOne(String code) {
        try {
            return Permission.valueOf(code);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Permiso desconocido: " + code);
        }
    }
}
