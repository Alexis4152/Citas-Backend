package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.response.*;
import com.hospital.citas.service.AvailabilityService;
import com.hospital.citas.service.BranchService;
import com.hospital.citas.service.DoctorService;
import com.hospital.citas.service.HospitalConfigService;
import com.hospital.citas.service.SpecialtyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicCatalogController {

    private final BranchService branchService;
    private final SpecialtyService specialtyService;
    private final DoctorService doctorService;
    private final AvailabilityService availabilityService;
    private final HospitalConfigService hospitalConfigService;

    // Sin datos sensibles (nombre, logo, color, contacto) -- expuesto público para que
    // Header/Footer/StaffLayout puedan pintar la marca real del hospital sin requerir sesión
    // de ADMIN (ver HospitalConfigContext.jsx, que antes vivía con un config hardcodeado
    // porque el único endpoint existente era /api/admin/hospital-config).
    @GetMapping("/hospital-config")
    public ApiResponse<HospitalConfigResponse> hospitalConfig() {
        return ApiResponse.ok(hospitalConfigService.get());
    }

    @GetMapping("/branches")
    public ApiResponse<List<BranchResponse>> branches() {
        return ApiResponse.ok(branchService.listActive());
    }

    @GetMapping("/specialties")
    public ApiResponse<List<SpecialtyResponse>> specialties() {
        return ApiResponse.ok(specialtyService.listActive());
    }

    @GetMapping("/doctors")
    public ApiResponse<PageResponse<DoctorSummaryResponse>> doctors(
            @RequestParam(required = false) Long specialtyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = doctorService.search(specialtyId, branchId, PageRequest.of(page, size));
        return ApiResponse.ok(PageResponse.of(result));
    }

    @GetMapping("/doctors/{id}")
    public ApiResponse<DoctorDetailResponse> doctor(@PathVariable Long id) {
        return ApiResponse.ok(doctorService.getDetail(id));
    }

    @GetMapping("/doctors/{id}/availability")
    public ApiResponse<List<DayAvailabilityResponse>> availability(
            @PathVariable Long id,
            @RequestParam LocalDate date,
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(availabilityService.getAvailability(id, date, days));
    }
}
