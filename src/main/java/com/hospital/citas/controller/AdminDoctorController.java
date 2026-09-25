package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.DoctorCreateRequest;
import com.hospital.citas.dto.request.DoctorUpdateRequest;
import com.hospital.citas.dto.response.DoctorDetailResponse;
import com.hospital.citas.dto.response.DoctorScheduleExceptionResponse;
import com.hospital.citas.dto.response.DoctorScheduleResponse;
import com.hospital.citas.dto.response.DoctorSummaryResponse;
import com.hospital.citas.service.DoctorScheduleService;
import com.hospital.citas.service.DoctorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/doctors")
@RequiredArgsConstructor
public class AdminDoctorController {

    private final DoctorService doctorService;
    private final DoctorScheduleService doctorScheduleService;

    @GetMapping
    public ApiResponse<PageResponse<DoctorSummaryResponse>> list(
            @RequestParam(required = false) Long specialtyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = doctorService.search(specialtyId, branchId, PageRequest.of(page, size));
        return ApiResponse.ok(PageResponse.of(result));
    }

    @GetMapping("/{id}")
    public ApiResponse<DoctorDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(doctorService.getDetail(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DoctorDetailResponse>> create(@Valid @RequestBody DoctorCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(doctorService.adminCreate(request), "Doctor creado correctamente"));
    }

    @PutMapping("/{id}")
    public ApiResponse<DoctorDetailResponse> update(@PathVariable Long id, @Valid @RequestBody DoctorUpdateRequest request) {
        return ApiResponse.ok(doctorService.adminUpdate(id, request), "Doctor actualizado correctamente");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deactivate(@PathVariable Long id) {
        doctorService.deactivate(id);
        return ApiResponse.ok(null, "Doctor desactivado");
    }

    @PutMapping("/{id}/photo")
    public ApiResponse<DoctorDetailResponse> uploadPhoto(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(doctorService.updatePhoto(id, file), "Foto actualizada correctamente");
    }

    // Solo lectura: el director del hospital necesita ver el horario/excepciones de
    // CUALQUIER doctor sin salir del panel administrativo. Reusa el mismo
    // DoctorScheduleService que ya usa el doctor para su propio horario (los métodos ya
    // reciben doctorId directo, sin acoplar la verificación de dueño que sí aplica
    // DoctorController para el caso "doctor viendo/editando lo suyo").
    @GetMapping("/{id}/schedule")
    public ApiResponse<List<DoctorScheduleResponse>> schedule(@PathVariable Long id) {
        return ApiResponse.ok(doctorScheduleService.list(id));
    }

    @GetMapping("/{id}/schedule-exceptions")
    public ApiResponse<List<DoctorScheduleExceptionResponse>> scheduleExceptions(@PathVariable Long id) {
        return ApiResponse.ok(doctorScheduleService.listExceptions(id));
    }
}
