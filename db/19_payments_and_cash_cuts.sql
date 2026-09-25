-- ============================================================
-- Hospital Citas - Precios, cobros y corte de caja
--  * doctors.consultation_price: precio de la consulta que define cada doctor.
--  * appointments.price / payment_status: precio de la cita al agendar (foto, no cambia si el
--    doctor cambia su precio después) y su estado de pago.
--  * payments: cada cobro (efectivo, tarjeta en terminal, OpenPay tarjeta/SPEI), en línea
--    (pago anticipado del paciente) o en recepción.
--  * cash_cuts: corte de caja por turno de cada recepcionista/admin (fondo inicial, cobros,
--    efectivo esperado vs. contado).
-- Script incremental:  psql -U postgres -d hospital_citas -f db/19_payments_and_cash_cuts.sql
-- ============================================================

ALTER TABLE doctors ADD COLUMN IF NOT EXISTS consultation_price NUMERIC(10,2)
    CHECK (consultation_price IS NULL OR consultation_price >= 0);

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS price NUMERIC(10,2);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS payment_status VARCHAR(15) NOT NULL DEFAULT 'UNPAID';
ALTER TABLE appointments DROP CONSTRAINT IF EXISTS appointments_payment_status_check;
ALTER TABLE appointments ADD CONSTRAINT appointments_payment_status_check
    CHECK (payment_status IN ('UNPAID', 'PENDING', 'PAID', 'REFUNDED'));

CREATE TABLE IF NOT EXISTS cash_cuts (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES users(id),
    status                  VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    opened_at               TIMESTAMP NOT NULL DEFAULT NOW(),
    opening_amount          NUMERIC(12,2) NOT NULL DEFAULT 0,
    opening_notes           TEXT,
    closed_at               TIMESTAMP,
    closed_by_user_id       BIGINT REFERENCES users(id),
    -- Foto de los totales al cerrar (mientras está abierto se calculan en vivo).
    total_cash              NUMERIC(12,2),
    total_card_terminal     NUMERIC(12,2),
    total_openpay           NUMERIC(12,2),
    appointments_count      INTEGER,
    expected_cash           NUMERIC(12,2),
    counted_cash            NUMERIC(12,2),
    difference              NUMERIC(12,2),
    closing_notes           TEXT
);
-- Un usuario solo puede tener un corte abierto a la vez.
CREATE UNIQUE INDEX IF NOT EXISTS ux_cash_cuts_one_open_per_user ON cash_cuts(user_id) WHERE status = 'OPEN';
CREATE INDEX IF NOT EXISTS idx_cash_cuts_user ON cash_cuts(user_id, opened_at DESC);

CREATE TABLE IF NOT EXISTS payments (
    id                      BIGSERIAL PRIMARY KEY,
    appointment_id          BIGINT NOT NULL REFERENCES appointments(id),
    amount                  NUMERIC(10,2) NOT NULL CHECK (amount > 0),
    method                  VARCHAR(20) NOT NULL CHECK (method IN ('CASH', 'CARD_TERMINAL', 'OPENPAY_CARD', 'OPENPAY_SPEI')),
    status                  VARCHAR(15) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'CANCELLED', 'REFUNDED')),
    channel                 VARCHAR(10) NOT NULL CHECK (channel IN ('ONLINE', 'RECEPTION')),
    -- Reembolso: NONE, REFUNDED (ya se devolvió) o REVIEW (a consideración del administrador).
    refund_status           VARCHAR(10) NOT NULL DEFAULT 'NONE' CHECK (refund_status IN ('NONE', 'REFUNDED', 'REVIEW')),
    refund_reason           VARCHAR(500),
    refunded_at             TIMESTAMP,
    refunded_by_user_id     BIGINT REFERENCES users(id),
    cash_received           NUMERIC(10,2),
    change_given            NUMERIC(10,2),
    reference               VARCHAR(100),
    openpay_transaction_id  VARCHAR(100),
    authorization_code      VARCHAR(50),
    spei_clabe              VARCHAR(30),
    spei_bank               VARCHAR(60),
    failure_reason          VARCHAR(500),
    cash_cut_id             BIGINT REFERENCES cash_cuts(id),
    received_by_user_id     BIGINT REFERENCES users(id),
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_payments_appointment ON payments(appointment_id);
CREATE INDEX IF NOT EXISTS idx_payments_cash_cut ON payments(cash_cut_id);
CREATE INDEX IF NOT EXISTS idx_payments_openpay_tx ON payments(openpay_transaction_id);
CREATE INDEX IF NOT EXISTS idx_payments_created ON payments(created_at);
