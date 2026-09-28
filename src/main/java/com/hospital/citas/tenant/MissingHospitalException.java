package com.hospital.citas.tenant;

import com.hospital.citas.exception.BusinessException;

/** La operación necesita saber el hospital y la petición no lo trae (falta el link /c/<slug>). */
public class MissingHospitalException extends BusinessException {
    public MissingHospitalException() {
        super("No se identificó el hospital. Entra desde el enlace de tu hospital o consultorio.");
    }
}
