-- ============================================================
-- Hospital Citas - Cierre de huecos funcionales (revision de agenda/citas)
--  * Control de versiones (bloqueo optimista) en citas: dos acciones simultaneas sobre la
--    misma cita ya no se pisan en silencio.
--  * Llegada del paciente (arrived_at) y sobrecupo (overbooked).
--  * Restriccion de exclusion en BD: un doctor no puede tener dos citas que se traslapen
--    en el tiempo (antes el indice unico solo cubria "misma hora de inicio").
--  * Anulacion de recetas (no se borran: quedan en el historial marcadas como anuladas).
--  * Nuevo tipo de notificacion (bloqueo de agenda con citas afectadas).
-- Script incremental: se aplica sobre una BD ya sembrada con
--   psql -U postgres -d hospital_citas -f db/17_functional_gaps.sql
-- ============================================================

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS arrived_at TIMESTAMP;
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS overbooked BOOLEAN NOT NULL DEFAULT FALSE;

-- El sobrecupo (cita extra sobre un horario ya ocupado, solo recepcion/admin) queda fuera de
-- ambas reglas de unicidad; el resto de las citas siguen sin poder repetir horario.
DROP INDEX IF EXISTS ux_appointments_slot;
CREATE UNIQUE INDEX IF NOT EXISTS ux_appointments_slot
    ON appointments(doctor_id, appointment_date, start_time)
    WHERE status <> 'CANCELLED' AND overbooked = FALSE;

-- btree_gist permite combinar "=" (doctor_id) con "&&" (traslape de rangos) en una sola
-- restriccion. En PostgreSQL 13+ es una extension "de confianza": la puede crear el dueno de
-- la base sin ser superusuario.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE appointments DROP CONSTRAINT IF EXISTS ex_appointments_no_overlap;
ALTER TABLE appointments ADD CONSTRAINT ex_appointments_no_overlap
    EXCLUDE USING gist (
        doctor_id WITH =,
        tsrange(appointment_date + start_time, appointment_date + end_time) WITH &&
    ) WHERE (status <> 'CANCELLED' AND overbooked = FALSE);

ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS voided_at TIMESTAMP;
ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS voided_by_user_id BIGINT REFERENCES users(id);
ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS void_reason VARCHAR(500);

ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('NEW_APPOINTMENT', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_RESCHEDULED', 'SCHEDULE_CONFLICT'));
