package com.hospital.citas.dto.request;

import com.hospital.citas.enums.ArcoRequestStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Resolver una solicitud ARCO ({@code PUT /api/admin/arco-requests/{id}/resolve}). */
@Data
public class ArcoRequestResolve {

    @NotNull(message = "Elige si se completa o se rechaza")
    private ArcoRequestStatus status;

    @NotBlank(message = "Explica qué se hizo (queda como constancia y se le envía a quien la pidió)")
    @Size(min = 10, max = 2000, message = "La nota debe tener entre 10 y 2000 caracteres")
    private String notes;

    @AssertTrue(message = "La resolución solo puede ser Completada o Rechazada")
    public boolean isFinalStatus() {
        return status == null || status != ArcoRequestStatus.PENDING;
    }
}
