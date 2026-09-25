-- ============================================================
-- Hospital Citas - Los horarios de los doctores no pueden salirse del horario de su sede.
-- Ajusta los que ya estaban fuera: se recortan al rango de la sede (ej. doctor 09:00-21:00 en
-- una sede que cierra a las 20:00 pasa a 09:00-20:00) y los que quedan completamente fuera
-- se desactivan. A partir de ahora el backend lo valida al crear/editar horarios y al cambiar
-- el horario de la sede (ver DoctorScheduleServiceImpl y BranchServiceImpl).
-- Las citas ya agendadas NO se tocan.
-- ============================================================

-- 1) Completamente fuera del horario de la sede -> desactivar.
UPDATE doctor_schedules s
SET is_active = FALSE, deleted_at = NOW()
FROM branches b
WHERE b.id = s.branch_id AND s.is_active
  AND b.open_time IS NOT NULL AND b.close_time IS NOT NULL
  AND GREATEST(s.start_time, b.open_time) >= LEAST(s.end_time, b.close_time);

-- 2) Parcialmente fuera -> recortar al rango de la sede.
UPDATE doctor_schedules s
SET start_time = GREATEST(s.start_time, b.open_time),
    end_time = LEAST(s.end_time, b.close_time),
    updated_at = NOW()
FROM branches b
WHERE b.id = s.branch_id AND s.is_active
  AND b.open_time IS NOT NULL AND b.close_time IS NOT NULL
  AND (s.start_time < b.open_time OR s.end_time > b.close_time);
