package com.hospital.citas.dto.request;

import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;

@Data
public class BranchRequest {

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 150, message = "El nombre no puede superar 150 caracteres")
    private String name;

    @Size(max = 255, message = "La dirección no puede superar 255 caracteres")
    private String address;

    @Size(max = 100, message = "La ciudad no puede superar 100 caracteres")
    private String city;

    @Pattern(regexp = ValidationPatterns.PHONE_REGEXP_OPTIONAL, message = ValidationPatterns.PHONE_MESSAGE)
    private String phone;

    private LocalTime openTime;
    private LocalTime closeTime;

    @jakarta.validation.constraints.AssertTrue(message = "Captura la hora de apertura y la de cierre (o deja ambas vacías)")
    public boolean isHoursComplete() {
        return (openTime == null) == (closeTime == null);
    }

    @jakarta.validation.constraints.AssertTrue(message = "La hora de cierre debe ser posterior a la de apertura")
    public boolean isHoursRangeValid() {
        return openTime == null || closeTime == null || closeTime.isAfter(openTime);
    }

    @jakarta.validation.constraints.Size(max = 120, message = "La colonia no puede superar 120 caracteres")
    private String neighborhood;

    @jakarta.validation.constraints.Size(max = 80, message = "El estado no puede superar 80 caracteres")
    private String state;

    @jakarta.validation.constraints.Pattern(regexp = com.hospital.citas.util.ValidationPatterns.POSTAL_CODE_REGEXP, message = "El código postal debe tener 5 dígitos")
    private String postalCode;
}
