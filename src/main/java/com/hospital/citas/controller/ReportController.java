package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.service.ReportExcelExporter;
import com.hospital.citas.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** Reportes (admin, y recepción/doctor si tienen el permiso; un doctor solo ve lo suyo). */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final ReportExcelExporter excelExporter;
    private final com.hospital.citas.report.ReportCatalog reportCatalog;
    private final com.hospital.citas.report.ReportFileWriter fileWriter;

    /** Reportes que puede abrir quien pregunta (los de dinero solo con "Ver montos en reportes"). */
    @GetMapping("/catalog")
    public ApiResponse<java.util.List<com.hospital.citas.report.ReportCatalog.Definition>> catalog() {
        return ApiResponse.ok(reportCatalog.catalog());
    }

    /** Vista previa en pantalla de un reporte del catálogo. */
    @GetMapping("/table/{key}")
    public ApiResponse<com.hospital.citas.report.ReportTable> table(@PathVariable String key,
                                                                    @RequestParam(required = false) LocalDate from,
                                                                    @RequestParam(required = false) LocalDate to,
                                                                    @RequestParam(required = false) Long doctorId,
                                                                    @RequestParam(required = false) Long branchId,
                                                                    @RequestParam(required = false) Long specialtyId) {
        return ApiResponse.ok(reportCatalog.build(key, reportService.scoped(from, to, doctorId, branchId, specialtyId)));
    }

    /** El mismo reporte en CSV, Excel o PDF. */
    @GetMapping("/table/{key}/export")
    public ResponseEntity<byte[]> exportTable(@PathVariable String key,
                                              @RequestParam(defaultValue = "xlsx") String format,
                                              @RequestParam(required = false) LocalDate from,
                                              @RequestParam(required = false) LocalDate to,
                                              @RequestParam(required = false) Long doctorId,
                                              @RequestParam(required = false) Long branchId,
                                              @RequestParam(required = false) Long specialtyId) {
        var filters = reportService.scoped(from, to, doctorId, branchId, specialtyId);
        var table = reportCatalog.build(key, filters);
        var fmt = com.hospital.citas.report.ReportFileWriter.Format.parse(format);
        return download(fileWriter.write(table, fmt, currentUserName()),
                com.hospital.citas.report.ReportFileWriter.fileName(table.title() + " " + filters.from() + " a " + filters.to(), fmt), fmt);
    }

    static ResponseEntity<byte[]> download(byte[] file, String name, com.hospital.citas.report.ReportFileWriter.Format fmt) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(fmt.contentType))
                .body(file);
    }

    static String currentUserName() {
        var user = com.hospital.citas.security.SecurityUtils.getCurrentUserOrNull();
        return user == null ? null : user.getFirstName() + " " + user.getLastName();
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(@RequestParam(required = false) LocalDate from,
                                                     @RequestParam(required = false) LocalDate to,
                                                     @RequestParam(required = false) Long doctorId,
                                                     @RequestParam(required = false) Long branchId,
                                                     @RequestParam(required = false) Long specialtyId) {
        return ApiResponse.ok(reportService.overview(reportService.scoped(from, to, doctorId, branchId, specialtyId)));
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to,
                                         @RequestParam(required = false) Long doctorId,
                                         @RequestParam(required = false) Long branchId,
                                         @RequestParam(required = false) Long specialtyId) {
        var filters = reportService.scoped(from, to, doctorId, branchId, specialtyId);
        byte[] file = excelExporter.export(filters);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reporte-" + filters.from() + "-a-" + filters.to() + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }
}
