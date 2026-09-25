-- ============================================================
-- Restricción opcional de recepcionista por especialidad: si una recepcionista tiene una o
-- más filas aquí, solo ve/agenda/administra citas de doctores de esas especialidades (ver
-- AppointmentServiceImpl). Sin filas = recepcionista general, sin restricción (comportamiento
-- de siempre). Se modela igual que doctor_branches (tabla intermedia simple, sin columnas
-- propias) en vez de una columna nueva en users, porque una recepcionista puede cubrir más
-- de una especialidad.
-- ============================================================

CREATE TABLE IF NOT EXISTS receptionist_specialties (
    user_id BIGINT NOT NULL REFERENCES users(id),
    specialty_id BIGINT NOT NULL REFERENCES specialties(id),
    PRIMARY KEY (user_id, specialty_id)
);

CREATE INDEX IF NOT EXISTS idx_receptionist_specialties_specialty_id
    ON receptionist_specialties(specialty_id);
