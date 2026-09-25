package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.EmailConfigRequest;
import com.hospital.citas.dto.response.EmailConfigResponse;
import com.hospital.citas.service.EmailConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/email-config")
@RequiredArgsConstructor
public class AdminEmailConfigController {

    private final EmailConfigService emailConfigService;

    @GetMapping
    public ApiResponse<EmailConfigResponse> get() {
        return ApiResponse.ok(emailConfigService.get());
    }

    @PutMapping
    public ApiResponse<EmailConfigResponse> update(@Valid @RequestBody EmailConfigRequest request) {
        return ApiResponse.ok(emailConfigService.update(request), "Configuración de correo actualizada");
    }
}
