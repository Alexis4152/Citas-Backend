package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.BranchRequest;
import com.hospital.citas.dto.response.BranchResponse;
import com.hospital.citas.service.BranchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/branches")
@RequiredArgsConstructor
public class AdminBranchController {

    private final BranchService branchService;

    @GetMapping
    public ApiResponse<PageResponse<BranchResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResponse.of(branchService.adminSearch(q, PageRequest.of(page, size))));
    }

    @PostMapping
    public ApiResponse<BranchResponse> create(@Valid @RequestBody BranchRequest request) {
        return ApiResponse.ok(branchService.create(request), "Sede creada");
    }

    @PutMapping("/{id}")
    public ApiResponse<BranchResponse> update(@PathVariable Long id, @Valid @RequestBody BranchRequest request) {
        return ApiResponse.ok(branchService.update(id, request), "Sede actualizada");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deactivate(@PathVariable Long id) {
        branchService.deactivate(id);
        return ApiResponse.ok(null, "Sede desactivada");
    }
}

