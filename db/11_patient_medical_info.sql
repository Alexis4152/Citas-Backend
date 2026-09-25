-- ============================================================
-- Alergias + tipo de sangre del paciente -- obligatorio en todo alta/agendado nuevo (el
-- doctor lo necesita para poder recetar con seguridad). "medical_info_updated_by/at" es un
-- rastro de auditoría DEDICADO (distinto de updated_by/updated_at, que se pisan con
-- cualquier otro cambio del paciente) para poder decir con certeza quién capturó/modificó
-- esta información médica y cuándo -- importante para responsabilidad legal.
-- ============================================================

ALTER TABLE patients ADD COLUMN IF NOT EXISTS allergies VARCHAR(150);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS blood_type VARCHAR(20);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS blood_type_other VARCHAR(50);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS medical_info_updated_by_user_id BIGINT REFERENCES users(id);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS medical_info_updated_at TIMESTAMP;
