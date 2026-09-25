-- ============================================================
-- Índices para las consultas más frecuentes/costosas sobre "appointments" -- antes solo
-- existían índices de una sola columna (doctor_id, appointment_date, patient_id, status),
-- así que las consultas que sí importan en agendar/reprogramar y en filtrar por sede
-- terminaban escaneando de más:
--
-- 1) El bloqueo pesimista al agendar/reprogramar (AppointmentRepository.lockOccupantsForSlot)
--    y el cálculo de horarios ocupados (findOccupiedSlots) filtran por
--    doctor_id + appointment_date (+ start_time) -- un índice compuesto cubre exactamente
--    ese patrón en vez de que Postgres combine dos índices de una sola columna.
-- 2) AppointmentSpecifications.search() filtra por branch_id (vista de Citas de
--    recepción/admin), columna que no tenía índice propio.
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_appointments_doctor_date_time
    ON appointments (doctor_id, appointment_date, start_time);

CREATE INDEX IF NOT EXISTS idx_appointments_branch_id
    ON appointments (branch_id);
