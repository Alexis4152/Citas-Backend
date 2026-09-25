-- ============================================================
-- Recetas médicas: el doctor las genera durante/tras atender una cita, con un PDF
-- profesional (datos del hospital, del doctor, del paciente y línea de firma física).
-- "prescription_template" es la plantilla base que cada doctor guarda en su perfil y que se
-- precarga como punto de partida al escribir una receta nueva (se edita libremente antes de
-- generar el PDF, no se usa tal cual).
-- ============================================================

ALTER TABLE doctors ADD COLUMN IF NOT EXISTS prescription_template TEXT;

CREATE TABLE IF NOT EXISTS prescriptions (
    id BIGSERIAL PRIMARY KEY,
    appointment_id BIGINT NOT NULL REFERENCES appointments(id),
    content TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id BIGINT REFERENCES users(id),
    updated_by_user_id BIGINT REFERENCES users(id),
    deleted_by_user_id BIGINT REFERENCES users(id),
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_prescriptions_appointment_id ON prescriptions(appointment_id);
