package com.hospital.citas.dto.request;

import com.hospital.citas.util.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Edita los datos administrables de un recepcionista ya existente. Sin email (cambiar el
 * correo es una operación de cuenta/auth aparte) ni password (la genera el servidor solo al
 * crear, ver ReceptionistServiceImpl#create). */
@Data
public class ReceptionistUpdateRequest {

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    @Size(max = 100, message = "El apellido no puede superar 100 caracteres")
    private String lastName;

    @Pattern(regexp = ValidationPatterns.PHONE_REGEXP_OPTIONAL, message = ValidationPatterns.PHONE_MESSAGE)
    private String phone;

    /** Reemplaza por completo la lista de especialidades asignadas -- vacío o nulo =
     * recepcionista general (sin restricción). */
    private List<Long> specialtyIds;
}
