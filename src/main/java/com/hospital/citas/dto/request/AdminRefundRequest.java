package com.hospital.citas.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminRefundRequest {
    @NotBlank(message = "El motivo del reembolso es obligatorio")
    @Size(max = 500, message = "El motivo no puede superar 500 caracteres")
    private String reason;

    /** true = el dinero ya se devolvió por fuera del sistema (transferencia bancaria en un pago
     * SPEI, efectivo en caja): solo se registra. false = se pide el reembolso a OpenPay (tarjeta). */
    private boolean manual;
}
