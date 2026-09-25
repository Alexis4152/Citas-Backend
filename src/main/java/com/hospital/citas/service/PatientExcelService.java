package com.hospital.citas.service;

import com.hospital.citas.dto.response.PatientImportSummary;
import org.springframework.web.multipart.MultipartFile;

public interface PatientExcelService {

    byte[] buildTemplate();

    byte[] exportAll();

    /** Reusa el MISMO validador que el alta individual ({@link PatientService#create}) fila
     * por fila (patrón 10): una fila mala no tumba el resto del lote. */
    PatientImportSummary importFromExcel(MultipartFile file);
}
