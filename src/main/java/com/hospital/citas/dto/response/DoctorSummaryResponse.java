package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DoctorSummaryResponse {
    private Long id;
    private String firstName;
    private String lastName;
    private SpecialtyResponse specialty;
    private String photoUrl;
    /** Precio de la consulta (nulo = sin definir). Público: el paciente lo ve al agendar. */
    private java.math.BigDecimal consultationPrice;
    private List<BranchResponse> branches;
}
