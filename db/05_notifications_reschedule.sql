-- ============================================================
-- Hospital Citas - Agrega APPOINTMENT_RESCHEDULED al check de
-- notifications.type (script incremental sobre 04_notifications.sql,
-- para BDs que ya tenian la tabla creada con el check viejo).
-- ============================================================

ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('NEW_APPOINTMENT', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_RESCHEDULED'));
