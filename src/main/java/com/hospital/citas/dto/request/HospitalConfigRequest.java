package com.hospital.citas.dto.request;

import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class HospitalConfigRequest {

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 150, message = "El nombre no puede superar 150 caracteres")
    private String name;

    @Size(max = 500, message = "La URL del logo es demasiado larga")
    private String logoUrl;
    @Pattern(regexp = com.hospital.citas.util.ValidationPatterns.HEX_COLOR_REGEXP, message = "El color debe tener el formato #RRGGBB")
    private String primaryColor;

    @Size(max = 1000, message = "La descripción no puede superar 1000 caracteres")
    private String description;

    @Pattern(regexp = ValidationPatterns.PHONE_REGEXP_OPTIONAL, message = ValidationPatterns.PHONE_MESSAGE)
    private String contactPhone;

    @Email(regexp = com.hospital.citas.util.ValidationPatterns.EMAIL_REGEXP, message = "El correo de contacto no es válido")
    private String contactEmail;

    @jakarta.validation.constraints.Size(max = 200, message = "La razón social no puede superar 200 caracteres")
    private String legalName;

    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.RFC_REGEXP, message = "El RFC no es válido (12 o 13 caracteres, en mayúsculas)")
    private String rfc;

    @jakarta.validation.constraints.Size(max = 200, message = "La calle y número no puede superar 200 caracteres")
    private String addressLine;

    @jakarta.validation.constraints.Size(max = 120, message = "La colonia no puede superar 120 caracteres")
    private String neighborhood;

    @jakarta.validation.constraints.Size(max = 100, message = "La ciudad no puede superar 100 caracteres")
    private String city;

    @jakarta.validation.constraints.Size(max = 80, message = "El estado no puede superar 80 caracteres")
    private String state;

    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.POSTAL_CODE_REGEXP, message = "El código postal debe tener 5 dígitos")
    private String postalCode;

    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.WEBSITE_REGEXP, message = "El sitio web debe empezar con http:// o https://")
    private String website;

    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.PHONE_REGEXP_OPTIONAL, message = "El WhatsApp debe tener 10 dígitos")
    private String whatsapp;

    @jakarta.validation.constraints.Size(max = 300, message = "El horario de atención no puede superar 300 caracteres")
    private String openingHours;

    /** null = no cambia. */
    private Boolean autoNoShowEnabled;

    @jakarta.validation.constraints.Min(value = 5, message = "La espera antes de marcar \"no asistió\" debe ser de al menos 5 minutos")
    @jakarta.validation.constraints.Max(value = 240, message = "La espera antes de marcar \"no asistió\" no puede superar 240 minutos")
    private Integer noShowGraceMinutes;
}
