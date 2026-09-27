package com.hospital.citas.controller;

import com.hospital.citas.controller.support.PdfDownloads;
import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.*;
import com.hospital.citas.dto.response.*;
import com.hospital.citas.service.AppointmentService;
import com.hospital.citas.service.DoctorScheduleService;
import com.hospital.citas.service.DoctorService;
import com.hospital.citas.service.PatientService;
import com.hospital.citas.service.PrescriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/doctor")
@RequiredArgsConstructor
public class DoctorController {

    private final DoctorService doctorService;
    private final DoctorScheduleService doctorScheduleService;
    private final AppointmentService appointmentService;
    private final PatientService patientService;
    private final PrescriptionService prescriptionService;

    @GetMapping("/me")
    public ApiResponse<DoctorDetailResponse> me() {
        return ApiResponse.ok(doctorService.getOwnProfile());
    }

    @PutMapping("/me")
    public ApiResponse<DoctorDetailResponse> updateMe(@Valid @RequestBody DoctorProfileUpdateRequest request) {
        return ApiResponse.ok(doctorService.updateOwnProfile(request), "Perfil actualizado");
    }

    /** El doctor define el precio de sus consultas (nulo/0 = sin precio). */
    @PutMapping("/me/price")
    public ApiResponse<DoctorDetailResponse> updatePrice(@Valid @RequestBody DoctorPriceRequest request) {
        return ApiResponse.ok(doctorService.updateOwnPrice(request.getPrice()), "Precio actualizado");
    }

    @GetMapping("/schedule")
    public ApiResponse<List<DoctorScheduleResponse>> listSchedule() {
        return ApiResponse.ok(doctorScheduleService.list(doctorService.getOwnDoctorEntity().getId()));
    }

    @PostMapping("/schedule")
    public ApiResponse<DoctorScheduleResponse> createSchedule(@Valid @RequestBody DoctorScheduleRequest request) {
        return ApiResponse.ok(
                doctorScheduleService.create(doctorService.getOwnDoctorEntity().getId(), request), "Horario creado");
    }

    @PutMapping("/schedule/{scheduleId}")
    public ApiResponse<DoctorScheduleResponse> updateSchedule(@PathVariable Long scheduleId,
                                                                @Valid @RequestBody DoctorScheduleRequest request) {
        return ApiResponse.ok(
                doctorScheduleService.update(doctorService.getOwnDoctorEntity().getId(), scheduleId, request),
                "Horario actualizado");
    }

    @DeleteMapping("/schedule/{scheduleId}")
    public ApiResponse<Void> deleteSchedule(@PathVariable Long scheduleId) {
        doctorScheduleService.delete(doctorService.getOwnDoctorEntity().getId(), scheduleId);
        return ApiResponse.ok(null, "Horario eliminado");
    }

    @GetMapping("/schedule-exceptions")
    public ApiResponse<List<DoctorScheduleExceptionResponse>> listScheduleExceptions() {
        return ApiResponse.ok(doctorScheduleService.listExceptions(doctorService.getOwnDoctorEntity().getId()));
    }

    @PostMapping("/schedule-exceptions")
    public ApiResponse<DoctorScheduleExceptionResponse> createScheduleException(
            @Valid @RequestBody DoctorScheduleExceptionRequest request) {
        return ApiResponse.ok(
                doctorScheduleService.createException(doctorService.getOwnDoctorEntity().getId(), request),
                "Ausencia registrada");
    }

    @DeleteMapping("/schedule-exceptions/{exceptionId}")
    public ApiResponse<Void> deleteScheduleException(@PathVariable Long exceptionId) {
        doctorScheduleService.deleteException(doctorService.getOwnDoctorEntity().getId(), exceptionId);
        return ApiResponse.ok(null, "Ausencia eliminada");
    }

    @GetMapping("/appointments")
    public ApiResponse<List<AppointmentResponse>> myAppointments(
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return ApiResponse.ok(appointmentService.listForOwnDoctor(from, to));
    }

    // Vista paginada de "Mis citas" (filtros de fecha/estado/paciente) -- distinta de
    // /appointments de arriba, que trae todo el rango sin paginar para armar el calendario
    // semanal de "Mi agenda".
    @GetMapping("/appointments/search")
    public ApiResponse<PageResponse<AppointmentResponse>> searchMyAppointments(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String patientQuery,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = appointmentService.searchOwnDoctor(branchId, from, to, status, patientQuery, PageRequest.of(page, size));
        return ApiResponse.ok(PageResponse.of(result));
    }

    @PostMapping("/appointments/{id}/cancel")
    public ApiResponse<AppointmentResponse> cancel(@PathVariable Long id,
                                                     @Valid @RequestBody(required = false) CancelAppointmentRequest request) {
        return ApiResponse.ok(appointmentService.cancelByStaff(id, request), "Cita cancelada correctamente");
    }

    @PatchMapping("/appointments/{id}/release-slot")
    public ApiResponse<AppointmentResponse> releaseSlot(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.releaseSlot(id), "Espacio liberado correctamente");
    }

    @PatchMapping("/appointments/{id}/reschedule")
    public ApiResponse<AppointmentResponse> reschedule(@PathVariable Long id, @Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.ok(appointmentService.reschedule(id, request), "Cita reprogramada correctamente");
    }

    @PatchMapping("/appointments/{id}/arrived")
    public ApiResponse<AppointmentResponse> arrived(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.markArrived(id), "Llegada del paciente registrada");
    }

    @PatchMapping("/appointments/{id}/complete")
    public ApiResponse<AppointmentResponse> complete(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.markCompleted(id), "Cita marcada como atendida");
    }

    @PatchMapping("/appointments/{id}/no-show")
    public ApiResponse<AppointmentResponse> noShow(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.markNoShow(id), "Cita marcada como no asistió");
    }

    /** El doctor puede actualizar alergias/tipo de sangre de cualquier paciente que esté
     * viendo (ej. desde el detalle de una cita) -- necesita poder corregir/completar esta
     * información para recetar con seguridad, no solo leerla. */
    @PutMapping("/patients/{id}/medical-info")
    public ApiResponse<PatientResponse> updatePatientMedicalInfo(@PathVariable Long id, @Valid @RequestBody MedicalInfoRequest request) {
        return ApiResponse.ok(patientService.updateMedicalInfo(id, request), "Información médica actualizada");
    }

    @GetMapping("/appointments/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        return PdfDownloads.attachment(appointmentService.generateReceiptPdfForDoctor(id), "comprobante-cita-" + id + ".pdf");
    }

    // ── Recetas ──────────────────────────────────────────────────

    @PostMapping("/prescriptions")
    public ResponseEntity<ApiResponse<PrescriptionResponse>> createPrescription(@Valid @RequestBody PrescriptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(prescriptionService.create(request), "Receta generada correctamente"));
    }

    /** Anula (con motivo) una receta propia -- no se borra ni se edita, ver PrescriptionService.voidPrescription. */
    @PostMapping("/prescriptions/{id}/void")
    public ApiResponse<PrescriptionResponse> voidPrescription(@PathVariable Long id,
                                                                @Valid @RequestBody com.hospital.citas.dto.request.VoidPrescriptionRequest request) {
        return ApiResponse.ok(prescriptionService.voidPrescription(id, request.getReason()), "Receta anulada");
    }

    @GetMapping("/prescriptions/{id}/pdf")
    public ResponseEntity<byte[]> prescriptionPdf(@PathVariable Long id) {
        return PdfDownloads.attachment(prescriptionService.generatePdfForDoctor(id), "receta-" + id + ".pdf");
    }

    /** El doctor decide caso por caso -- nunca automático (ver PrescriptionService.sendByEmail). */
    @PostMapping("/prescriptions/{id}/send-email")
    public ApiResponse<Void> sendPrescriptionEmail(@PathVariable Long id) {
        prescriptionService.sendByEmail(id);
        return ApiResponse.ok(null, "Receta enviada al correo del paciente");
    }

    /** Historial paginado y buscable (por nombre de paciente) de todas las recetas que el
     * doctor autenticado ha emitido -- módulo "Recetas". */
    @GetMapping("/prescriptions")
    public ApiResponse<PageResponse<PrescriptionResponse>> searchPrescriptions(
            @RequestParam(required = false) String patientQuery,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(prescriptionService.searchForOwnDoctor(patientQuery, PageRequest.of(page, size)));
    }

    /** Recetas ya generadas para una cita puntual -- se muestran en el detalle de esa cita en
     * "Mi agenda" para no perder de vista lo que ya se recetó ahí. */
    @GetMapping("/appointments/{id}/prescriptions")
    public ApiResponse<List<PrescriptionResponse>> appointmentPrescriptions(@PathVariable Long id) {
        return ApiResponse.ok(prescriptionService.listForAppointmentAsDoctor(id));
    }
}
