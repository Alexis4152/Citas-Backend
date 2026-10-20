-- 25 · Citas cuya hora ya pasó sin que nadie confirmara si el paciente llegó.
-- Antes se marcaban SIEMPRE como "no asistió" 15 minutos después de terminar. Ahora cada
-- hospital decide: por defecto queda apagado y recepción las confirma en "Cobrar"
-- ("Confirma si llegó": Llegó → Cobrar / No asistió).
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS auto_no_show_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS no_show_grace_minutes INTEGER NOT NULL DEFAULT 30;
