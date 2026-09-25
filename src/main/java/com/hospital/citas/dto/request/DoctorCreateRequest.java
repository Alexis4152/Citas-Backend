package com.hospital.citas.dto.request;

import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/** Crea de un solo golpe el User (rol DOCTOR), el perfil Doctor y su asignación a sedes. */
@Data
public class DoctorCreateRequest {

    @NotBlank @Email(message = "El correo no es válido")
    private String email;

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    @Size(max = 100, message = "El apellido no puede superar 100 caracteres")
    private String lastName;

    @Pattern(regexp = ValidationPatterns.PHONE_REGEXP_OPTIONAL, message = ValidationPatterns.PHONE_MESSAGE)
    private String phone;

    @NotNull(message = "La especialidad es obligatoria")
    private Long specialtyId;

    @Size(max = 50, message = "La cédula profesional no puede superar 50 caracteres")
    private String licenseNumber;

    @Size(max = 2000, message = "La biografía no puede superar 2000 caracteres")
    private String bio;

    private String photoUrl;

    @jakarta.validation.constraints.DecimalMin(value = "0.00", message = "El precio no puede ser negativo")
    @jakarta.validation.constraints.DecimalMax(value = "999999.99", message = "El precio es demasiado alto")
    private java.math.BigDecimal consultationPrice;

    @Min(value = 5, message = "La duración del espacio debe ser de al menos 5 minutos")
    @Max(value = 240, message = "La duración del espacio no puede superar 240 minutos")
    private Integer defaultSlotMinutes;

    @NotEmpty(message = "Debe asignarse al menos una sede")
    private List<Long> branchIds;

    @Valid
    private List<DoctorScheduleRequest> schedules;
}
