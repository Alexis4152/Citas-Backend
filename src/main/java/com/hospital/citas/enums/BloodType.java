package com.hospital.citas.enums;

/** Tipo de sangre del paciente, capturado al agendar/registrar (obligatorio -- el doctor lo
 * necesita para poder recetar con seguridad). {@link #OTHER} cubre casos raros/no
 * estandarizados, con el detalle libre en {@code Patient#bloodTypeOther}. */
public enum BloodType {
    A_POSITIVE,
    A_NEGATIVE,
    B_POSITIVE,
    B_NEGATIVE,
    AB_POSITIVE,
    AB_NEGATIVE,
    O_POSITIVE,
    O_NEGATIVE,
    OTHER
}
