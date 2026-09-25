-- ============================================================
-- Auditoría de quién libera el espacio de una cita cancelada (doctor o recepción/admin) --
-- antes solo se guardaba el booleano slot_released, sin registrar quién ni cuándo.
-- ============================================================

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS released_by_user_id BIGINT REFERENCES users(id);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS released_at TIMESTAMP;
