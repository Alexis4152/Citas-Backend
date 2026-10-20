-- ============================================================
-- 24: roles y permisos configurables por hospital (Recepción y Doctor).
-- Sin filas = permisos de fábrica (ver Permission.java). Idempotente.
-- ============================================================

BEGIN;

CREATE TABLE IF NOT EXISTS role_permissions (
    hospital_id  BIGINT      NOT NULL REFERENCES hospitals(id),
    role         VARCHAR(30) NOT NULL CHECK (role IN ('RECEPTIONIST', 'DOCTOR')),
    permission   VARCHAR(60) NOT NULL,
    granted      BOOLEAN     NOT NULL,
    PRIMARY KEY (hospital_id, role, permission)
);

-- Excepciones por persona: dar (GRANT) o quitar (DENY) un permiso a alguien en particular.
CREATE TABLE IF NOT EXISTS user_permission_overrides (
    hospital_id  BIGINT      NOT NULL REFERENCES hospitals(id),
    user_id      BIGINT      NOT NULL REFERENCES users(id),
    permission   VARCHAR(60) NOT NULL,
    effect       VARCHAR(5)  NOT NULL CHECK (effect IN ('GRANT', 'DENY')),
    PRIMARY KEY (user_id, permission)
);
CREATE INDEX IF NOT EXISTS idx_user_permission_overrides_hospital ON user_permission_overrides (hospital_id);

COMMIT;
