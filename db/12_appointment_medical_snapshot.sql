-- ============================================================
-- Foto de alergias/tipo de sangre AL MOMENTO DE AGENDAR, guardada en la propia cita --
-- separada del dato "vigente" del paciente (patients.allergies/blood_type), que se sigue
-- actualizando en cada cita nueva. Sin esto, el comprobante de una cita vieja mostraba el
-- dato MÁS RECIENTE del paciente (que pudo cambiar después), no el que era cierto cuando
-- ocurrió esa cita -- un problema para la validez legal del comprobante.
-- ============================================================

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS allergies_snapshot VARCHAR(150);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS blood_type_snapshot VARCHAR(20);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS blood_type_other_snapshot VARCHAR(50);

-- Citas ya existentes: se rellenan con el dato actual del paciente como mejor esfuerzo
-- (no hay forma de saber retroactivamente qué era cierto en ese momento).
UPDATE appointments a
SET allergies_snapshot = p.allergies,
    blood_type_snapshot = p.blood_type,
    blood_type_other_snapshot = p.blood_type_other
FROM patients p
WHERE a.patient_id = p.id AND a.allergies_snapshot IS NULL;
