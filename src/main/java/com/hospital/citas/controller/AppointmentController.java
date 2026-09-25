package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.AppointmentRequest;
import com.hospital.citas.dto.request.CancelAppointmentRequest;
import com.hospital.citas.dto.request.GuestAppointmentRequest;
import com.hospital.citas.dto.request.GuestLookupRequest;
import com.hospital.citas.dto.request.RescheduleRequest;
import com.hospital.citas.controller.support.PdfDownloads;
import com.hospital.citas.dto.response.AppointmentResponse;
import com.hospital.citas.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping("/guest")
    public ResponseEntity<ApiResponse<AppointmentResponse>> bookGuest(@Valid @RequestBody GuestAppointmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(appointmentService.bookGuest(request), "Cita agendada correctamente"));
    }

    /** Público: para el invitado que perdió su código de cancelación. Sin fecha/hora NO devuelve
     * citas: manda los enlaces al correo que dejó al agendar y responde siempre igual. Con la
     * fecha y hora exactas de la cita (vía para quien no dejó correo) devuelve esa cita, sin
     * motivo de consulta ni datos médicos (ver AppointmentService.requestGuestAppointmentLinks). */
    @PostMapping("/guest-lookup")
    public ApiResponse<AppointmentResponse> guestLookup(@Valid @RequestBody GuestLookupRequest request) {
        AppointmentResponse direct = appointmentService.requestGuestAppointmentLinks(request.getPhone(),
                request.getFirstName(), request.getLastName(), request.getAppointmentDate(), request.getStartTime());
        if (direct != null) {
            return ApiResponse.ok(direct);
        }
        return ApiResponse.ok(null, "Si encontramos citas con esos datos y un correo registrado, te enviamos los enlaces por correo.");
    }

    /** Público, con el token de la cita: qué cita es (para cancelar/reprogramar con contexto). */
    @GetMapping("/by-token/{token}")
    public ApiResponse<AppointmentResponse> getByToken(@PathVariable UUID token) {
        return ApiResponse.ok(appointmentService.getByToken(token));
    }

    @PostMapping("/cancel/{token}")
    public ApiResponse<AppointmentResponse> cancelByToken(@PathVariable UUID token,
                                                            @Valid @RequestBody(required = false) CancelAppointmentRequest request) {
        return ApiResponse.ok(appointmentService.cancelByToken(token, request), "Cita cancelada correctamente");
    }

    /** Público: el invitado reprograma su cita con el token (mismo doctor, con anticipación). */
    @PostMapping("/reschedule/{token}")
    public ApiResponse<AppointmentResponse> rescheduleByToken(@PathVariable UUID token,
                                                                @Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.ok(appointmentService.rescheduleByToken(token, request), "Cita reprogramada correctamente");
    }

    @PatchMapping("/{id}/reschedule")
    public ApiResponse<AppointmentResponse> rescheduleOwn(@PathVariable Long id,
                                                            @Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.ok(appointmentService.rescheduleOwn(id, request), "Cita reprogramada correctamente");
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AppointmentResponse>> bookSelf(@Valid @RequestBody AppointmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(appointmentService.bookSelf(request), "Cita agendada correctamente"));
    }

    @GetMapping
    public ApiResponse<List<AppointmentResponse>> listOwn() {
        return ApiResponse.ok(appointmentService.listOwn());
    }

    // Vista paginada/filtrable de "Mis citas" (fecha, especialidad, doctor) -- distinta del
    // listado plano de arriba, que usa la sección "Próximas" para traer todo sin paginar.
    @GetMapping("/search")
    public ApiResponse<PageResponse<AppointmentResponse>> searchOwn(
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long specialtyId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = appointmentService.searchOwn(doctorId, specialtyId, from, to, status, PageRequest.of(page, size));
        return ApiResponse.ok(PageResponse.of(result));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<AppointmentResponse> cancelOwn(@PathVariable Long id,
                                                        @Valid @RequestBody(required = false) CancelAppointmentRequest request) {
        return ApiResponse.ok(appointmentService.cancelOwn(id, request), "Cita cancelada correctamente");
    }

    @GetMapping("/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        return PdfDownloads.attachment(appointmentService.generateReceiptPdfOwn(id), "comprobante-cita-" + id + ".pdf");
    }

    /** Público: mismo modelo de confianza que {@code /cancel/{token}} (patrón 09/11). */
    @GetMapping("/guest-receipt/{token}")
    public ResponseEntity<byte[]> guestReceipt(@PathVariable UUID token) {
        return PdfDownloads.attachment(appointmentService.generateReceiptPdfByToken(token), "comprobante-cita.pdf");
    }
}
