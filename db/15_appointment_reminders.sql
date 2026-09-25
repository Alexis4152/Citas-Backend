-- Recordatorio automático por correo 2 horas antes de la cita (roadmap 5.1). Este timestamp
-- evita mandar el recordatorio más de una vez a la misma cita si el job vuelve a correr antes
-- de que el correo anterior termine de enviarse (el envío es async).
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS reminder_2h_sent_at TIMESTAMP;
