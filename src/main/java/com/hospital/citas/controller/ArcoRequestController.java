package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.ArcoRequestCreate;
import com.hospital.citas.dto.request.ArcoRequestResolve;
import com.hospital.citas.dto.response.ArcoRequestResponse;
import com.hospital.citas.enums.ArcoRequestStatus;
import com.hospital.citas.service.ArcoRequestService;
import com.hospital.citas.tenant.TenantContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Solicitudes ARCO, en tres puertas:
 * <ul>
 *   <li>{@code /api/public/arco-requests}: formulario público del hospital (sin sesión).</li>
 *   <li>{@code /api/arco-requests}: "Mis solicitudes" de quien tiene sesión en ese hospital.</li>
 *   <li>{@code /api/admin/arco-requests}: el ADMIN del hospital las atiende.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class ArcoRequestController {

    private final ArcoRequestService arcoRequestService;

    @PostMapping("/api/public/arco-requests")
    public ResponseEntity<ApiResponse<ArcoRequestResponse>> createPublic(@Valid @RequestBody ArcoRequestCreate request) {
        TenantContext.requireHospitalId();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(arcoRequestService.createPublic(request),
                "Recibimos tu solicitud. Te responderemos a tu correo dentro del plazo legal."));
    }

    @PostMapping("/api/arco-requests")
    public ResponseEntity<ApiResponse<ArcoRequestResponse>> createOwn(@Valid @RequestBody ArcoRequestCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(arcoRequestService.createOwn(request), "Solicitud enviada"));
    }

    @GetMapping("/api/arco-requests/mine")
    public ApiResponse<List<ArcoRequestResponse>> mine() {
        return ApiResponse.ok(arcoRequestService.listMine());
    }

    @GetMapping("/api/admin/arco-requests")
    public ApiResponse<List<ArcoRequestResponse>> list(@RequestParam(required = false) ArcoRequestStatus status) {
        return ApiResponse.ok(arcoRequestService.list(status));
    }

    @GetMapping("/api/admin/arco-requests/pending-count")
    public ApiResponse<Long> pendingCount() {
        return ApiResponse.ok(arcoRequestService.countPending());
    }

    @PutMapping("/api/admin/arco-requests/{id}/resolve")
    public ApiResponse<ArcoRequestResponse> resolve(@PathVariable Long id, @Valid @RequestBody ArcoRequestResolve request) {
        return ApiResponse.ok(arcoRequestService.resolve(id, request), "Solicitud resuelta; se le avisó por correo");
    }

    /** Derecho de Acceso: los datos personales del solicitante en un JSON descargable. */
    @GetMapping("/api/admin/arco-requests/{id}/export")
    public ResponseEntity<Map<String, Object>> export(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"datos-personales-arco-" + id + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(arcoRequestService.exportData(id));
    }

    /** Supervisión de la plataforma: las solicitudes de todos los hospitales. */
    @GetMapping("/api/superadmin/arco-requests")
    public ApiResponse<List<ArcoRequestResponse>> listAll(@RequestParam(required = false) ArcoRequestStatus status) {
        return ApiResponse.ok(arcoRequestService.listAllHospitals(status));
    }
}
