package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** Bitácora de acceso a datos sensibles del hospital (solo ADMIN, ver SecurityConfig /api/admin/**). */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditService auditService;

    @GetMapping
    public ApiResponse<PageResponse<Map<String, Object>>> search(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(auditService.search(action, entityType, entityId, user, from, to, page, size));
    }

    @GetMapping("/actions")
    public ApiResponse<Map<String, String>> actions() {
        return ApiResponse.ok(AuditService.ACTION_LABELS);
    }
}
