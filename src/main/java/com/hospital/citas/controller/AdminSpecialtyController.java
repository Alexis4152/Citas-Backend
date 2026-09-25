package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.SpecialtyRequest;
import com.hospital.citas.dto.response.SpecialtyResponse;
import com.hospital.citas.service.SpecialtyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/specialties")
@RequiredArgsConstructor
public class AdminSpecialtyController {

    private final SpecialtyService specialtyService;

    @GetMapping
    public ApiResponse<PageResponse<SpecialtyResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResponse.of(specialtyService.adminSearch(q, PageRequest.of(page, size))));
    }

    @PostMapping
    public ApiResponse<SpecialtyResponse> create(@Valid @RequestBody SpecialtyRequest request) {
        return ApiResponse.ok(specialtyService.create(request), "Especialidad creada");
    }

    @PutMapping("/{id}")
    public ApiResponse<SpecialtyResponse> update(@PathVariable Long id, @Valid @RequestBody SpecialtyRequest request) {
        return ApiResponse.ok(specialtyService.update(id, request), "Especialidad actualizada");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deactivate(@PathVariable Long id) {
        specialtyService.deactivate(id);
        return ApiResponse.ok(null, "Especialidad desactivada");
    }
}
