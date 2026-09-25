package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.ReceptionistCreateRequest;
import com.hospital.citas.dto.request.ReceptionistUpdateRequest;
import com.hospital.citas.dto.response.UserResponse;
import com.hospital.citas.service.ReceptionistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/receptionists")
@RequiredArgsConstructor
public class AdminReceptionistController {

    private final ReceptionistService receptionistService;

    @GetMapping
    public ApiResponse<PageResponse<UserResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResponse.of(receptionistService.search(q, PageRequest.of(page, size))));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> create(@Valid @RequestBody ReceptionistCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(receptionistService.create(request), "Recepcionista creado correctamente"));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserResponse> update(@PathVariable Long id, @Valid @RequestBody ReceptionistUpdateRequest request) {
        return ApiResponse.ok(receptionistService.update(id, request), "Recepcionista actualizado correctamente");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deactivate(@PathVariable Long id) {
        receptionistService.deactivate(id);
        return ApiResponse.ok(null, "Recepcionista desactivado");
    }
}
