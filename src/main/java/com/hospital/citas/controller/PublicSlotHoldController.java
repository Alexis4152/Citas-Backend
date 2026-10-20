package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.SlotHoldRequest;
import com.hospital.citas.dto.response.SlotHoldResponse;
import com.hospital.citas.service.SlotHoldService;
import com.hospital.citas.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Apartado temporal de un horario mientras se termina de agendar (público: lo usan invitados y
 * pacientes con cuenta). Limitado por IP en {@code RateLimitInterceptor}.
 */
@RestController
@RequestMapping("/api/public/slot-holds")
@RequiredArgsConstructor
public class PublicSlotHoldController {

    private final SlotHoldService slotHoldService;

    @PostMapping
    public ApiResponse<SlotHoldResponse> hold(@Valid @RequestBody SlotHoldRequest request, HttpServletRequest http) {
        TenantContext.requireHospitalId();
        return ApiResponse.ok(slotHoldService.hold(request, http.getRemoteAddr()), "Horario apartado");
    }

    @DeleteMapping("/{token}")
    public ApiResponse<Void> release(@PathVariable UUID token) {
        TenantContext.requireHospitalId();
        slotHoldService.release(token);
        return ApiResponse.ok(null, "Horario liberado");
    }
}
