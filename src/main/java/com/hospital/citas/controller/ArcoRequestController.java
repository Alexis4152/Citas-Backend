package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.ArcoActions;
import com.hospital.citas.dto.request.ArcoRequestCreate;
import com.hospital.citas.dto.response.ArcoRequestResponse;
import com.hospital.citas.entity.ArcoDocument;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Solicitudes ARCO, en cuatro puertas:
 * <ul>
 *   <li>{@code /api/public/arco-requests} y {@code /api/public/arco/track/{token}}: formulario
 *   público y portal de seguimiento del titular (con el enlace que le llega por correo).</li>
 *   <li>{@code /api/arco-requests}: el paciente con sesión.</li>
 *   <li>{@code /api/admin/arco-requests}: el ADMIN del hospital las atiende.</li>
 *   <li>{@code /api/superadmin/arco-requests}: supervisión de la plataforma.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class ArcoRequestController {

    private final ArcoRequestService arcoRequestService;

    // ── Público ─────────────────────────────────────────────────

    @PostMapping("/api/public/arco-requests")
    public ResponseEntity<ApiResponse<ArcoRequestResponse>> createPublic(@Valid @RequestBody ArcoRequestCreate request) {
        TenantContext.requireHospitalId();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(arcoRequestService.createPublic(request),
                "Si el correo está registrado en el hospital, te enviamos un enlace: ábrelo para que tu solicitud quede registrada."));
    }

    @GetMapping("/api/public/arco/track/{token}")
    public ApiResponse<Map<String, Object>> track(@PathVariable String token) {
        return ApiResponse.ok(arcoRequestService.track(token));
    }

    @PostMapping("/api/public/arco/track/{token}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable String token) {
        return ApiResponse.ok(arcoRequestService.confirmEmail(token), "Correo confirmado: tu solicitud quedó registrada");
    }

    @PostMapping(value = "/api/public/arco/track/{token}/identity", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadIdentity(@PathVariable String token, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(arcoRequestService.uploadIdentity(token, file), "Recibimos tu identificación");
    }

    @PostMapping(value = "/api/public/arco/track/{token}/info", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> answerInfo(@PathVariable String token,
                                                       @RequestParam(value = "message", required = false) String message,
                                                       @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResponse.ok(arcoRequestService.answerInfo(token, message, file), "Recibimos tu respuesta");
    }

    @GetMapping("/api/public/arco/track/{token}/documents/{documentId}")
    public ResponseEntity<byte[]> titularDocument(@PathVariable String token, @PathVariable Long documentId) {
        return file(arcoRequestService.titularDocument(token, documentId));
    }

    // ── Paciente con sesión ─────────────────────────────────────

    @PostMapping("/api/arco-requests")
    public ResponseEntity<ApiResponse<ArcoRequestResponse>> createOwn(@Valid @RequestBody ArcoRequestCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(arcoRequestService.createOwn(request), "Solicitud enviada"));
    }

    @GetMapping("/api/arco-requests/mine")
    public ApiResponse<List<ArcoRequestResponse>> mine() {
        return ApiResponse.ok(arcoRequestService.listMine());
    }

    @GetMapping("/api/arco-requests/{id}/tracking")
    public ApiResponse<String> myTracking(@PathVariable Long id) {
        return ApiResponse.ok(arcoRequestService.myTrackingToken(id));
    }

    // ── Administrador del hospital ──────────────────────────────

    @GetMapping("/api/admin/arco-requests")
    public ApiResponse<List<ArcoRequestResponse>> list(@RequestParam(required = false) ArcoRequestStatus status) {
        return ApiResponse.ok(arcoRequestService.list(status));
    }

    @GetMapping("/api/admin/arco-requests/pending-count")
    public ApiResponse<Long> pendingCount() {
        return ApiResponse.ok(arcoRequestService.countPending());
    }

    @GetMapping("/api/admin/arco-requests/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(arcoRequestService.detail(id));
    }

    @PostMapping("/api/admin/arco-requests/in-person")
    public ResponseEntity<ApiResponse<ArcoRequestResponse>> inPerson(@RequestBody ArcoActions.InPerson request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(arcoRequestService.createInPerson(request), "Solicitud registrada"));
    }

    @PostMapping("/api/admin/arco-requests/{id}/verify-identity")
    public ApiResponse<ArcoRequestResponse> verify(@PathVariable Long id, @RequestBody ArcoActions.Verify request) {
        return ApiResponse.ok(arcoRequestService.verifyIdentity(id, request), "Identidad verificada");
    }

    @PostMapping("/api/admin/arco-requests/{id}/request-info")
    public ApiResponse<ArcoRequestResponse> requestInfo(@PathVariable Long id, @RequestBody ArcoActions.RequestInfo request) {
        return ApiResponse.ok(arcoRequestService.requestInfo(id, request), "Se le pidió la información al titular");
    }

    @PostMapping("/api/admin/arco-requests/{id}/extend")
    public ApiResponse<ArcoRequestResponse> extend(@PathVariable Long id, @RequestBody ArcoActions.Extend request) {
        return ApiResponse.ok(arcoRequestService.extend(id, request), "Plazo ampliado");
    }

    @PostMapping("/api/admin/arco-requests/{id}/respond")
    public ApiResponse<ArcoRequestResponse> respond(@PathVariable Long id, @RequestBody ArcoActions.Respond request) {
        return ApiResponse.ok(arcoRequestService.respond(id, request), "Respuesta enviada al titular");
    }

    @PostMapping("/api/admin/arco-requests/{id}/execute")
    public ApiResponse<ArcoRequestResponse> execute(@PathVariable Long id, @RequestBody ArcoActions.Execute request) {
        return ApiResponse.ok(arcoRequestService.execute(id, request), "Derecho hecho efectivo; se le avisó al titular");
    }

    @GetMapping("/api/admin/arco-requests/{id}/documents/{documentId}")
    public ResponseEntity<byte[]> adminDocument(@PathVariable Long id, @PathVariable Long documentId) {
        return file(arcoRequestService.adminDocument(id, documentId));
    }

    @GetMapping("/api/admin/arco-requests/{id}/constancia")
    public ResponseEntity<byte[]> constancia(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"constancia-ARCO-" + String.format("%06d", id) + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(arcoRequestService.constancia(id));
    }

    // ── Plataforma ──────────────────────────────────────────────

    @GetMapping("/api/superadmin/arco-requests")
    public ApiResponse<List<ArcoRequestResponse>> listAll(@RequestParam(required = false) ArcoRequestStatus status) {
        return ApiResponse.ok(arcoRequestService.listAllHospitals(status));
    }

    private ResponseEntity<byte[]> file(ArcoDocument d) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.getFilename() + "\"")
                .header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType(d.getContentType()))
                .body(arcoRequestService.content(d));
    }
}
