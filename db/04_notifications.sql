-- ============================================================
-- Hospital Citas - Notificaciones en app (doctor/admin) para
-- nuevas citas y cancelaciones. Script incremental (no toca
-- 01_schema.sql) para poder aplicarse sobre una BD ya sembrada.
-- ============================================================

CREATE TABLE IF NOT EXISTS notifications (
    id              BIGSERIAL PRIMARY KEY,
    recipient_id    BIGINT NOT NULL REFERENCES users(id),
    type            VARCHAR(30) NOT NULL CHECK (type IN ('NEW_APPOINTMENT', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_RESCHEDULED')),
    message         VARCHAR(500) NOT NULL,
    appointment_id  BIGINT REFERENCES appointments(id),
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON notifications(recipient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_unread ON notifications(recipient_id, is_read) WHERE is_read = FALSE;
