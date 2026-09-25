package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.HospitalConfigRequest;
import com.hospital.citas.dto.response.HospitalConfigResponse;
import com.hospital.citas.service.HospitalConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/hospital-config")
@RequiredArgsConstructor
public class AdminHospitalConfigController {

    private final HospitalConfigService hospitalConfigService;

    @GetMapping
    public ApiResponse<HospitalConfigResponse> get() {
        return ApiResponse.ok(hospitalConfigService.get());
    }

    @PutMapping
    public ApiResponse<HospitalConfigResponse> update(@Valid @RequestBody HospitalConfigRequest request) {
        return ApiResponse.ok(hospitalConfigService.update(request), "Configuración del hospital actualizada");
    }

    @PutMapping("/logo")
    public ApiResponse<HospitalConfigResponse> uploadLogo(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(hospitalConfigService.updateLogo(file), "Logo actualizado correctamente");
    }
}
