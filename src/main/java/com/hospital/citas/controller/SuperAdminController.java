package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.HospitalAdminRequest;
import com.hospital.citas.dto.request.HospitalCreateRequest;
import com.hospital.citas.dto.request.HospitalUpdateRequest;
import com.hospital.citas.dto.response.HospitalResponse;
import com.hospital.citas.service.SuperAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Panel de plataforma (Nexora): hospitales/consultorios clientes. Solo SUPER_ADMIN (ver SecurityConfig). */
@RestController
@RequestMapping("/api/superadmin/hospitals")
@RequiredArgsConstructor
public class SuperAdminController {

    private final SuperAdminService superAdminService;

    @GetMapping
    public ApiResponse<List<HospitalResponse>> list() {
        return ApiResponse.ok(superAdminService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<HospitalResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(superAdminService.get(id));
    }

    @PostMapping
    public ApiResponse<HospitalResponse> create(@Valid @RequestBody HospitalCreateRequest request) {
        return ApiResponse.ok(superAdminService.create(request), "Hospital creado");
    }

    @PutMapping("/{id}")
    public ApiResponse<HospitalResponse> update(@PathVariable Long id, @Valid @RequestBody HospitalUpdateRequest request) {
        return ApiResponse.ok(superAdminService.update(id, request), "Hospital actualizado");
    }

    @PostMapping("/{id}/admins")
    public ApiResponse<HospitalResponse> addAdmin(@PathVariable Long id, @Valid @RequestBody HospitalAdminRequest request) {
        return ApiResponse.ok(superAdminService.addAdmin(id, request), "Administrador creado");
    }
}
