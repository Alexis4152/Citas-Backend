-- ============================================================
-- Recomendaciones al paciente por especialidad (ej. "Acude con la vejiga llena" en
-- Ginecología) -- reemplaza el texto genérico de "llega 15 min antes..." en la confirmación,
-- el correo y el comprobante en PDF cuando el ADMIN la captura. Vacía = sigue usando el
-- texto genérico de siempre.
-- ============================================================

ALTER TABLE specialties ADD COLUMN IF NOT EXISTS recommendations TEXT;
