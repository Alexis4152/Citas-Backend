-- ============================================================
-- 21: apartado temporal de horarios, solicitudes ARCO y cifrado de credenciales.
-- Idempotente: se puede correr más de una vez.
-- ============================================================

BEGIN;

-- ============ APARTADO TEMPORAL DE HORARIOS ============
-- Mientras alguien llena sus datos para agendar, el horario queda apartado unos minutos y
-- los demás lo ven como "alguien lo está apartando". Si no confirma a tiempo, el apartado
-- vence solo y el horario vuelve a estar libre (ver SlotHoldService).
CREATE TABLE IF NOT EXISTS slot_holds (
    id           BIGSERIAL PRIMARY KEY,
    hospital_id  BIGINT      NOT NULL REFERENCES hospitals(id),
    doctor_id    BIGINT      NOT NULL REFERENCES doctors(id),
    branch_id    BIGINT      NOT NULL REFERENCES branches(id),
    hold_date    DATE        NOT NULL,
    start_time   TIME        NOT NULL,
    end_time     TIME        NOT NULL,
    -- Identificador aleatorio de la pestaña del navegador que aparta (no es un dato personal).
    holder_key   VARCHAR(64) NOT NULL,
    -- Se devuelve solo a quien apartó: con él confirma la cita o suelta el horario.
    token        UUID        NOT NULL UNIQUE,
    client_ip    VARCHAR(64),
    expires_at   TIMESTAMP   NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);
-- Un solo apartado por horario: si dos personas lo intentan al mismo tiempo, la segunda choca aquí.
CREATE UNIQUE INDEX IF NOT EXISTS ux_slot_holds_slot ON slot_holds (doctor_id, hold_date, start_time);
CREATE INDEX IF NOT EXISTS idx_slot_holds_hospital ON slot_holds (hospital_id);
CREATE INDEX IF NOT EXISTS idx_slot_holds_holder ON slot_holds (holder_key);
CREATE INDEX IF NOT EXISTS idx_slot_holds_expires ON slot_holds (expires_at);

-- ============ SOLICITUDES ARCO ============
-- Derechos de Acceso, Rectificación, Cancelación y Oposición (LFPDPPP) sobre los datos
-- personales que guarda cada hospital. Las atiende el ADMIN de ese hospital.
CREATE TABLE IF NOT EXISTS arco_requests (
    id                   BIGSERIAL PRIMARY KEY,
    hospital_id          BIGINT       NOT NULL REFERENCES hospitals(id),
    requester_name       VARCHAR(150) NOT NULL,
    requester_email      VARCHAR(150) NOT NULL,
    requester_phone      VARCHAR(30),
    type                 VARCHAR(20)  NOT NULL CHECK (type IN ('ACCESS', 'RECTIFICATION', 'CANCELLATION', 'OPPOSITION')),
    status               VARCHAR(15)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'REJECTED')),
    details              TEXT,
    resolution_notes     TEXT,
    requester_user_id    BIGINT       REFERENCES users(id),
    patient_id           BIGINT       REFERENCES patients(id),
    resolved_by_user_id  BIGINT       REFERENCES users(id),
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- La ley da 20 días hábiles para responder: se guarda la fecha límite al recibirla.
    due_date             DATE         NOT NULL,
    resolved_at          TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_arco_requests_hospital_status ON arco_requests (hospital_id, status);
CREATE INDEX IF NOT EXISTS idx_arco_requests_requester ON arco_requests (requester_user_id);

-- ============ CREDENCIALES CIFRADAS ============
-- La llave privada de OpenPay y la contraseña SMTP ahora se guardan cifradas (AES-256-GCM):
-- el texto cifrado es más largo que el original. Los valores ya guardados en claro se siguen
-- leyendo y se cifran la próxima vez que el admin los guarde.
ALTER TABLE hospitals ALTER COLUMN openpay_private_key TYPE VARCHAR(512);
ALTER TABLE email_config ALTER COLUMN smtp_password TYPE VARCHAR(512);

COMMIT;
