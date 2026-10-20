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
    private final com.hospital.citas.service.AvailabilityEvents availabilityEvents;
    private final com.hospital.citas.tenant.HospitalDirectory hospitalDirectory;

    // Sin datos sensibles (nombre, logo, color, contacto) -- expuesto público para que
    // Header/Footer/StaffLayout puedan pintar la marca real del hospital sin requerir sesión
    // de ADMIN (ver HospitalConfigContext.jsx, que antes vivía con un config hardcodeado
    // porque el único endpoint existente era /api/admin/hospital-config).
    @GetMapping("/hospital-config")
    public ApiResponse<HospitalConfigResponse> hospitalConfig() {
        return ApiResponse.ok(hospitalConfigService.get());
    }

    /** Hospital al que pertenece una cita por su token (el filtro ya fijó ese hospital a partir
     * del token -- ver TokenTenantResolver). El frontend lo usa para mandar los enlaces viejos,
     * sin /c/<slug>, al link correcto del hospital. */
    @GetMapping("/appointment-hospital/{token}")
    public ApiResponse<HospitalConfigResponse> appointmentHospital(@PathVariable java.util.UUID token) {
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
        // Tope de página: sin él, ?size=1000000 obligaría a cargar todo el catálogo de una vez.
        var result = doctorService.search(specialtyId, branchId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
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
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String holder) {
        // holder: pestaña que consulta (ver SlotHold). Lo que no tenga el formato esperado se ignora.
        String holderKey = holder != null && holder.matches("^[A-Za-z0-9-]{8,64}$") ? holder : null;
        return ApiResponse.ok(availabilityService.getAvailability(id, date, days, false, holderKey));
    }

    /**
     * Avisos en vivo de cambios en la agenda de este doctor (Server-Sent Events). El navegador
     * no puede mandar headers con EventSource, por eso el hospital va en {@code ?hospital=}.
     */
    @GetMapping(value = "/doctors/{id}/availability/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter availabilityStream(
            @PathVariable Long id, @RequestParam String hospital, jakarta.servlet.http.HttpServletResponse response) {
        Long hospitalId = hospital.matches("^[a-z0-9-]{1,60}$") ? hospitalDirectory.activeIdBySlug(hospital) : null;
        if (hospitalId == null) {
            throw new com.hospital.citas.exception.ResourceNotFoundException("Hospital no encontrado");
        }
        // Que ningún proxy (Traefik/nginx) junte los eventos en un búfer.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return availabilityEvents.subscribe(hospitalId, id);
    }
}
