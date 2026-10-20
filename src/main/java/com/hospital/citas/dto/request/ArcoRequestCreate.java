package com.hospital.citas.dto.request;

import com.hospital.citas.enums.ArcoRequestType;
import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Solicitud ARCO desde el formulario público del hospital ({@code POST /api/public/arco-requests})
 * o desde la cuenta del paciente ({@code POST /api/arco-requests}, donde nombre y correo salen de
 * la sesión y se ignoran los del cuerpo).
 */
@Data
public class ArcoRequestCreate {

    @Size(max = 150, message = "El nombre no puede tener más de 150 caracteres")
    @Pattern(regexp = ValidationPatterns.FULL_NAME_REGEXP, message = ValidationPatterns.NAME_MESSAGE)
    private String requesterName;

    @Email(regexp = ValidationPatterns.EMAIL_REGEXP, message = "El correo no es válido")
    @Size(max = 150, message = "El correo no puede tener más de 150 caracteres")
    private String requesterEmail;

    @Pattern(regexp = ValidationPatterns.PHONE_REGEXP_OPTIONAL, message = ValidationPatterns.PHONE_MESSAGE)
    private String requesterPhone;

    @NotNull(message = "Elige qué tipo de solicitud quieres hacer")
    private ArcoRequestType type;

    @Size(max = 2000, message = "El detalle no puede tener más de 2000 caracteres")
    private String details;

    /** Aceptación del aviso de privacidad: obligatoria para mandar la solicitud. */
    private boolean acceptPrivacyNotice;

    @jakarta.validation.constraints.AssertTrue(message = "Debes aceptar el aviso de privacidad para enviar la solicitud")
    public boolean isPrivacyNoticeAccepted() {
        return acceptPrivacyNotice;
    }

    /** Rectificación y Oposición necesitan saber qué dato corregir u a qué uso se opone. */
    @jakarta.validation.constraints.AssertTrue(message = "Para rectificar u oponerte, cuéntanos qué dato o qué uso (mínimo 10 caracteres)")
    public boolean isDetailsPresentWhenRequired() {
        if (type != ArcoRequestType.RECTIFICATION && type != ArcoRequestType.OPPOSITION) {
            return true;
        }
        return details != null && details.trim().length() >= 10;
    }

    public void requireIdentityFields() {
        if (requesterName == null || requesterName.isBlank()) {
            throw new com.hospital.citas.exception.BusinessException("Tu nombre es obligatorio");
        }
        if (requesterEmail == null || requesterEmail.isBlank()) {
            throw new com.hospital.citas.exception.BusinessException("Tu correo es obligatorio");
        }
    }
}
