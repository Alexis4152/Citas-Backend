-- ============================================================
-- 22: bitácora de acceso a datos sensibles y contadores de rate limiting compartidos.
-- Idempotente: se puede correr más de una vez.
-- ============================================================

BEGIN;

-- ============ BITÁCORA (quién vio o cambió qué expediente) ============
CREATE TABLE IF NOT EXISTS audit_logs (
    id           BIGSERIAL PRIMARY KEY,
    hospital_id  BIGINT       NOT NULL REFERENCES hospitals(id),
    user_id      BIGINT       REFERENCES users(id),
    user_name    VARCHAR(150),
    user_role    VARCHAR(30),
    action       VARCHAR(40)  NOT NULL,
    entity_type  VARCHAR(30),
    entity_id    BIGINT,
    path         VARCHAR(255),
    client_ip    VARCHAR(64),
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_logs_hospital_created ON audit_logs (hospital_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_entity ON audit_logs (hospital_id, entity_type, entity_id);

-- ============ RATE LIMITING COMPARTIDO (APP_RATE_LIMIT_STORE=database) ============
-- Solo se usa con varias instancias del backend; con una sola, los contadores van en memoria.
CREATE TABLE IF NOT EXISTS rate_limit_hits (
    bucket_key    VARCHAR(200) NOT NULL,
    window_start  TIMESTAMP    NOT NULL,
    hits          INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (bucket_key, window_start)
);

COMMIT;
