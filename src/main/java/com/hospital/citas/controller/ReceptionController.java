package com.hospital.citas.controller;

import com.hospital.citas.controller.support.PdfDownloads;
import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.CancelAppointmentRequest;
import com.hospital.citas.dto.request.MedicalInfoRequest;
import com.hospital.citas.dto.request.PatientRequest;
import com.hospital.citas.dto.request.ReceptionAppointmentRequest;
import com.hospital.citas.dto.request.RescheduleRequest;
import com.hospital.citas.dto.response.AppointmentResponse;
import com.hospital.citas.dto.response.PatientImportSummary;
import com.hospital.citas.dto.response.PatientResponse;
import com.hospital.citas.dto.response.PrescriptionResponse;
import com.hospital.citas.service.AppointmentService;
import com.hospital.citas.service.PatientExcelService;
import com.hospital.citas.service.PatientService;
import com.hospital.citas.service.PrescriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reception")
@RequiredArgsConstructor
public class ReceptionController {

    private final AppointmentService appointmentService;
    private final PatientService patientService;
    private final PatientExcelService patientExcelService;
    private final PrescriptionService prescriptionService;
    private final com.hospital.citas.service.AvailabilityService availabilityService;

    @GetMapping("/appointments")
    public ApiResponse<PageResponse<AppointmentResponse>> search(
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String patientQuery,
            @RequestParam(defaultValue = "false") boolean pendingRelease,
            @RequestParam(defaultValue = "false") boolean pendingPayment,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = appointmentService.search(doctorId, patientId, branchId, from, to, status, patientQuery, pendingRelease, pendingPayment, PageRequest.of(page, size));
        return ApiResponse.ok(PageResponse.of(result));
    }

    @PostMapping("/appointments")
    public ResponseEntity<ApiResponse<AppointmentResponse>> book(@Valid @RequestBody ReceptionAppointmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(appointmentService.bookForPatient(request), "Cita agendada correctamente"));
    }

    @PostMapping("/appointments/{id}/cancel")
    public ApiResponse<AppointmentResponse> cancel(@PathVariable Long id,
                                                     @Valid @RequestBody(required = false) CancelAppointmentRequest request) {
        return ApiResponse.ok(appointmentService.cancelByStaff(id, request), "Cita cancelada correctamente");
    }

    @PatchMapping("/appointments/{id}/reschedule")
    public ApiResponse<AppointmentResponse> reschedule(@PathVariable Long id, @Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.ok(appointmentService.reschedule(id, request), "Cita reprogramada correctamente");
    }

    /** Recepción/admin puede liberar el espacio de la cita cancelada de CUALQUIER doctor
     * (a diferencia de DoctorController, que solo permite al doctor liberar las suyas) --
     * mismo servicio, que ya registra quién libera (releasedBy) para control de auditoría. */
    @PatchMapping("/appointments/{id}/release-slot")
    public ApiResponse<AppointmentResponse> releaseSlot(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.releaseSlot(id), "Espacio liberado correctamente");
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

    @GetMapping("/appointments/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        return PdfDownloads.attachment(appointmentService.generateReceiptPdfForStaff(id), "comprobante-cita-" + id + ".pdf");
    }

    /** Módulo de cobro: busca la cita por QR (URL o código), folio (#123) o nombre/teléfono del
     * paciente; sin texto, la cola de cobro. */
    @GetMapping("/charge/lookup")
    public ApiResponse<List<AppointmentResponse>> chargeLookup(@RequestParam(required = false) String q) {
        return ApiResponse.ok(appointmentService.lookupForCharge(q));
    }

    @GetMapping("/patients")
    public ApiResponse<PageResponse<PatientResponse>> searchPatients(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long specialtyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = patientService.search(q, doctorId, specialtyId, PageRequest.of(page, size));
        return ApiResponse.ok(PageResponse.of(result));
    }

    /** Posibles duplicados (mismo teléfono o correo) para sugerir fusionar registros. */
    @GetMapping("/patients/{id}/duplicates")
    public ApiResponse<List<PatientResponse>> patientDuplicates(@PathVariable Long id) {
        return ApiResponse.ok(patientService.findDuplicates(id));
    }

    /** Fusiona el paciente {id} (origen) dentro de {targetId}: sus citas y recetas pasan al
     * destino y el origen queda inactivo -- nada se borra. */
    @PostMapping("/patients/{id}/merge-into/{targetId}")
    public ApiResponse<PatientResponse> mergePatients(@PathVariable Long id, @PathVariable Long targetId) {
        return ApiResponse.ok(patientService.merge(id, targetId), "Pacientes fusionados correctamente");
    }

    /** Disponibilidad con reglas de recepción: sin la anticipación mínima de 30 minutos y
     * marcando los espacios ocupados que aún se pueden empalmar como sobrecupo. */
    @GetMapping("/doctors/{doctorId}/availability")
    public ApiResponse<List<com.hospital.citas.dto.response.DayAvailabilityResponse>> availability(
            @PathVariable Long doctorId,
            @RequestParam LocalDate date,
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(availabilityService.getAvailability(doctorId, date, days, true));
    }

    @GetMapping("/patients/{id}")
    public ApiResponse<PatientResponse> getPatient(@PathVariable Long id) {
        return ApiResponse.ok(patientService.getById(id));
    }

    @PutMapping("/patients/{id}/medical-info")
    public ApiResponse<PatientResponse> updatePatientMedicalInfo(@PathVariable Long id, @Valid @RequestBody MedicalInfoRequest request) {
        return ApiResponse.ok(patientService.updateMedicalInfo(id, request), "Información médica actualizada");
    }

    /** Solo lectura -- la receta la genera el doctor (ver DoctorController); recepción/admin
     * solo pueden consultar el historial ya emitido de un paciente y descargar el PDF. */
    @GetMapping("/patients/{id}/prescriptions")
    public ApiResponse<List<PrescriptionResponse>> patientPrescriptions(@PathVariable Long id) {
        patientService.requireAccessible(id);
        return ApiResponse.ok(prescriptionService.listForPatientAsStaff(id));
    }

    @GetMapping("/prescriptions/{id}/pdf")
    public ResponseEntity<byte[]> prescriptionPdf(@PathVariable Long id) {
        return PdfDownloads.attachment(prescriptionService.generatePdfForStaff(id), "receta-" + id + ".pdf");
    }

    @PostMapping("/patients")
    public ResponseEntity<ApiResponse<PatientResponse>> createPatient(@Valid @RequestBody PatientRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(patientService.create(request), "Paciente creado correctamente"));
    }

    @GetMapping("/patients/template.xlsx")
    public ResponseEntity<byte[]> patientsTemplate() {
        return excelAttachment(patientExcelService.buildTemplate(), "plantilla-pacientes.xlsx");
    }

    @GetMapping("/patients/export.xlsx")
    public ResponseEntity<byte[]> exportPatients() {
        return excelAttachment(patientExcelService.exportAll(), "pacientes.xlsx");
    }

    @PostMapping("/patients/import")
    public ApiResponse<PatientImportSummary> importPatients(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(patientExcelService.importFromExcel(file));
    }

    private ResponseEntity<byte[]> excelAttachment(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(bytes);
    }
}
