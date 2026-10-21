-- 26 · ARCO completo (modelo tipo Mercado Libre) y un correo = un paciente por hospital.
--  * Un correo solo puede pertenecer a UN paciente vigente dentro de cada hospital (en otro
--    hospital sí puede repetirse). Los duplicados que ya existían conservan el correo en el
--    registro principal; en los demás se mueve a email_moved para que recepción los revise o fusione.
--  * Solicitudes ARCO con confirmación de correo, verificación de identidad, requerimiento de
--    información (10 días hábiles), prórroga, respuesta formal y ejecución (15 días hábiles).
--  * Bloqueo: los datos de identidad de quien pide Cancelación se separan a un resguardo cifrado
--    (privacy_vault) durante el plazo legal de conservación; al vencer se borran (anonimización).
--  * Consentimientos y versiones del aviso de privacidad; preferencias de uso (oposición).
--  * Días inhábiles oficiales para contar los plazos.
BEGIN;

-- ============ Pacientes: correo único, bloqueo y preferencias ============
ALTER TABLE patients ADD COLUMN IF NOT EXISTS email_moved VARCHAR(150);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS privacy_status VARCHAR(15) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE patients ADD COLUMN IF NOT EXISTS blocked_at TIMESTAMP;
ALTER TABLE patients ADD COLUMN IF NOT EXISTS retain_until DATE;
ALTER TABLE patients ADD COLUMN IF NOT EXISTS anonymized_at TIMESTAMP;
ALTER TABLE patients ADD COLUMN IF NOT EXISTS notify_reminders BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE patients ADD COLUMN IF NOT EXISTS notify_news BOOLEAN NOT NULL DEFAULT FALSE;

-- Duplicados existentes: se queda el correo el paciente con cuenta; si no, el más antiguo.
WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (
               PARTITION BY hospital_id, LOWER(email)
               ORDER BY (user_id IS NULL), id) AS rn
    FROM patients
    WHERE email IS NOT NULL AND email <> '' AND is_active = TRUE
)
UPDATE patients p SET email_moved = p.email, email = NULL
FROM ranked r WHERE p.id = r.id AND r.rn > 1;

UPDATE patients SET email = NULL WHERE email = '';
CREATE UNIQUE INDEX IF NOT EXISTS ux_patients_hospital_email
    ON patients (hospital_id, LOWER(email)) WHERE email IS NOT NULL AND is_active = TRUE;

-- ============ Configuración de privacidad del hospital ============
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS privacy_contact_name VARCHAR(150);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS privacy_contact_email VARCHAR(150);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS privacy_notice_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS privacy_notice_updated_at TIMESTAMP;
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS clinical_retention_years INTEGER NOT NULL DEFAULT 5;
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS fiscal_retention_years INTEGER NOT NULL DEFAULT 5;

-- ============ Solicitudes ARCO: flujo completo ============
ALTER TABLE arco_requests DROP CONSTRAINT IF EXISTS arco_requests_type_check;
ALTER TABLE arco_requests ADD CONSTRAINT arco_requests_type_check
    CHECK (type IN ('ACCESS', 'RECTIFICATION', 'CANCELLATION', 'OPPOSITION', 'REVOCATION', 'LIMITATION'));
ALTER TABLE arco_requests DROP CONSTRAINT IF EXISTS arco_requests_status_check;
ALTER TABLE arco_requests ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE arco_requests ADD CONSTRAINT arco_requests_status_check
    CHECK (status IN ('PENDING_EMAIL', 'PENDING', 'INFO_REQUESTED', 'APPROVED', 'COMPLETED', 'REJECTED', 'NOT_FILED'));
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS channel VARCHAR(15) NOT NULL DEFAULT 'WEB';
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS tracking_token_hash VARCHAR(64);
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS tracking_token_enc TEXT;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS received_at TIMESTAMP;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMP;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS is_representative BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS representative_name VARCHAR(150);
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS identity_verified_at TIMESTAMP;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS identity_method VARCHAR(25);
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS identity_verified_by_user_id BIGINT REFERENCES users(id);
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS identity_notes TEXT;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS info_request TEXT;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS info_due DATE;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS info_response TEXT;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS extended BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS extension_reason TEXT;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS outcome VARCHAR(15);
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS legal_basis TEXT;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS execution_due DATE;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS executed_at TIMESTAMP;
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS executed_by_user_id BIGINT REFERENCES users(id);
ALTER TABLE arco_requests ADD COLUMN IF NOT EXISTS execution_summary TEXT;
UPDATE arco_requests SET received_at = created_at, email_verified_at = created_at WHERE received_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_arco_tracking_token ON arco_requests (tracking_token_hash) WHERE tracking_token_hash IS NOT NULL;

-- Línea de tiempo de cada solicitud (constancia de lo que se hizo y cuándo).
CREATE TABLE IF NOT EXISTS arco_events (
    id              BIGSERIAL PRIMARY KEY,
    hospital_id     BIGINT      NOT NULL REFERENCES hospitals(id),
    arco_request_id BIGINT      NOT NULL REFERENCES arco_requests(id),
    actor_user_id   BIGINT      REFERENCES users(id),
    actor_label     VARCHAR(150),
    type            VARCHAR(30) NOT NULL,
    message         TEXT        NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_arco_events_request ON arco_events (arco_request_id, created_at);

-- Identificaciones, respuestas a requerimientos, reportes de Acceso y constancias: cifrados.
CREATE TABLE IF NOT EXISTS arco_documents (
    id              BIGSERIAL PRIMARY KEY,
    hospital_id     BIGINT       NOT NULL REFERENCES hospitals(id),
    arco_request_id BIGINT       NOT NULL REFERENCES arco_requests(id),
    kind            VARCHAR(15)  NOT NULL CHECK (kind IN ('IDENTITY', 'INFO', 'DELIVERY', 'CONSTANCIA')),
    filename        VARCHAR(200) NOT NULL,
    content_type    VARCHAR(80)  NOT NULL,
    size_bytes      INTEGER      NOT NULL,
    data            BYTEA,
    uploaded_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_arco_documents_request ON arco_documents (arco_request_id);

-- Tokens de un solo uso enviados por correo (activar cuenta y ligar expediente).
CREATE TABLE IF NOT EXISTS email_tokens (
    id          BIGSERIAL PRIMARY KEY,
    hospital_id BIGINT      NOT NULL REFERENCES hospitals(id),
    purpose     VARCHAR(25) NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    user_id     BIGINT      REFERENCES users(id),
    patient_id  BIGINT      REFERENCES patients(id),
    expires_at  TIMESTAMP   NOT NULL,
    used_at     TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Resguardo (bloqueo): la identidad separada y cifrada mientras corre el plazo legal.
CREATE TABLE IF NOT EXISTS privacy_vault (
    id              BIGSERIAL PRIMARY KEY,
    hospital_id     BIGINT    NOT NULL REFERENCES hospitals(id),
    patient_id      BIGINT    NOT NULL REFERENCES patients(id),
    arco_request_id BIGINT    REFERENCES arco_requests(id),
    payload         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    retain_until    DATE      NOT NULL,
    purged_at       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_privacy_vault_patient ON privacy_vault (patient_id);

-- Notas aclaratorias al expediente (la NOM-004 no permite alterar lo clínico: se aclara).
CREATE TABLE IF NOT EXISTS patient_clarifications (
    id                 BIGSERIAL PRIMARY KEY,
    hospital_id        BIGINT    NOT NULL REFERENCES hospitals(id),
    patient_id         BIGINT    NOT NULL REFERENCES patients(id),
    arco_request_id    BIGINT    REFERENCES arco_requests(id),
    note               TEXT      NOT NULL,
    created_by_user_id BIGINT    REFERENCES users(id),
    created_at         TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_patient_clarifications_patient ON patient_clarifications (patient_id);

-- Consentimientos: qué versión del aviso aceptó cada quien, cuándo y por qué medio.
CREATE TABLE IF NOT EXISTS consent_records (
    id             BIGSERIAL PRIMARY KEY,
    hospital_id    BIGINT      NOT NULL REFERENCES hospitals(id),
    patient_id     BIGINT      REFERENCES patients(id),
    user_id        BIGINT      REFERENCES users(id),
    notice_version INTEGER     NOT NULL,
    scope          VARCHAR(20) NOT NULL,
    granted        BOOLEAN     NOT NULL,
    channel        VARCHAR(20) NOT NULL,
    ip             VARCHAR(64),
    created_at     TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_consent_records_patient ON consent_records (patient_id);

-- Versiones publicadas del aviso de privacidad (para consultar las anteriores).
CREATE TABLE IF NOT EXISTS privacy_notice_versions (
    id           BIGSERIAL PRIMARY KEY,
    hospital_id  BIGINT    NOT NULL REFERENCES hospitals(id),
    version      INTEGER   NOT NULL,
    published_at TIMESTAMP NOT NULL DEFAULT NOW(),
    snapshot     TEXT      NOT NULL,
    UNIQUE (hospital_id, version)
);

-- Días inhábiles oficiales (art. 74 de la Ley Federal del Trabajo) para contar plazos ARCO.
CREATE TABLE IF NOT EXISTS non_business_days (
    holiday_date DATE PRIMARY KEY,
    description VARCHAR(120) NOT NULL
);
INSERT INTO non_business_days (holiday_date, description) VALUES
    ('2025-01-01', 'Año Nuevo'), ('2025-02-03', 'Día de la Constitución'), ('2025-03-17', 'Natalicio de Benito Juárez'),
    ('2025-05-01', 'Día del Trabajo'), ('2025-09-16', 'Día de la Independencia'), ('2025-11-17', 'Día de la Revolución'),
    ('2025-12-25', 'Navidad'),
    ('2026-01-01', 'Año Nuevo'), ('2026-02-02', 'Día de la Constitución'), ('2026-03-16', 'Natalicio de Benito Juárez'),
    ('2026-05-01', 'Día del Trabajo'), ('2026-09-16', 'Día de la Independencia'), ('2026-11-16', 'Día de la Revolución'),
    ('2026-12-25', 'Navidad'),
    ('2027-01-01', 'Año Nuevo'), ('2027-02-01', 'Día de la Constitución'), ('2027-03-15', 'Natalicio de Benito Juárez'),
    ('2027-05-01', 'Día del Trabajo'), ('2027-09-16', 'Día de la Independencia'), ('2027-11-15', 'Día de la Revolución'),
    ('2027-12-25', 'Navidad'),
    ('2028-01-01', 'Año Nuevo'), ('2028-02-07', 'Día de la Constitución'), ('2028-03-20', 'Natalicio de Benito Juárez'),
    ('2028-05-01', 'Día del Trabajo'), ('2028-09-16', 'Día de la Independencia'), ('2028-11-20', 'Día de la Revolución'),
    ('2028-12-25', 'Navidad')
ON CONFLICT (holiday_date) DO NOTHING;

COMMIT;
