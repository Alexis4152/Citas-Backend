package com.hospital.citas.controller.support;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Arma la respuesta HTTP de un PDF descargable, reusado por los endpoints de comprobante de
 * cita en {@code AppointmentController}, {@code ReceptionController} y {@code DoctorController}. */
public final class PdfDownloads {

    private PdfDownloads() {
    }

    public static ResponseEntity<byte[]> attachment(byte[] pdfBytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(pdfBytes);
    }
}
