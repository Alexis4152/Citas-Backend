package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Resumen de una importación de pacientes por Excel: una fila mala no tumba el resto del
 * lote (patrón 10), cada una se valida por separado con el mismo validador del alta individual. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PatientImportSummary {
    private int created;
    private List<RowError> errors;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RowError {
        private int row;
        private String message;
    }
}
