-- ============================================================
-- Hospital Citas - init.sql
-- Base de datos NUEVA y lista para usar: esquema completo (equivale a 01_schema.sql + las
-- migraciones 03 a 19 ya aplicadas) + datos minimos para poder entrar al sistema:
--   * los 4 roles,
--   * la configuracion del hospital (editable desde el panel de admin),
--   * la configuracion de correo (deshabilitada; se captura en el panel),
--   * un usuario ADMIN:  ing.arturopineda.94@gmail.com
-- No incluye doctores, sedes ni especialidades de demo: el admin los da de alta desde el panel.
--
-- Uso (en una BD vacia):
--   psql -U postgres -d hospital_citas -f db/init.sql
-- Es idempotente: correrlo de nuevo no duplica nada ni pisa el usuario admin existente.
--
-- Para una BD que ya venia de 01_schema.sql/02_seed.sql, NO uses este script: aplica las
-- migraciones incrementales que le falten (db/03_*.sql ... db/19_*.sql).
-- ============================================================

-- btree_gist: necesaria para la restriccion que impide citas traslapadas del mismo doctor.
-- (PostgreSQL 13+: extension "de confianza", la puede crear el dueno de la BD.)
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ============ ROLES Y USUARIOS ============

CREATE TABLE IF NOT EXISTS roles (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(30) NOT NULL UNIQUE CHECK (name IN ('ADMIN', 'RECEPTIONIST', 'DOCTOR', 'PATIENT')),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS users (
    id                      BIGSERIAL PRIMARY KEY,
    email                   VARCHAR(150) NOT NULL UNIQUE,
    password_hash           VARCHAR(255) NOT NULL,
    first_name              VARCHAR(100) NOT NULL,
    last_name               VARCHAR(100) NOT NULL,
    phone                   VARCHAR(30),
    role_id                 BIGINT NOT NULL REFERENCES roles(id),
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password    BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts   INT NOT NULL DEFAULT 0,
    locked_until            TIMESTAMP,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id      BIGINT REFERENCES users(id),
    updated_at              TIMESTAMP,
    updated_by_user_id      BIGINT REFERENCES users(id),
    deleted_at              TIMESTAMP,
    deleted_by_user_id      BIGINT REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_role_id ON users(role_id);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    token_hash      VARCHAR(255) NOT NULL UNIQUE,
    expires_at      TIMESTAMP NOT NULL,
    revoked_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    token           UUID NOT NULL UNIQUE,
    expires_at      TIMESTAMP NOT NULL,
    used_at         TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);

-- ============ CONFIGURACION DEL HOSPITAL (fila unica) ============

CREATE TABLE IF NOT EXISTS hospital_config (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(150) NOT NULL,
    logo_url            VARCHAR(500),
    primary_color       VARCHAR(10) NOT NULL DEFAULT '#0F766E',
    description         VARCHAR(500),
    contact_phone       VARCHAR(30),
    contact_email       VARCHAR(150),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by_user_id  BIGINT REFERENCES users(id)
);

-- ============ CONFIGURACION DE CORREO (SMTP, fila unica) ============
-- La contrasena nunca se devuelve al frontend; se captura desde /api/admin/email-config.

CREATE TABLE IF NOT EXISTS email_config (
    id                  BIGSERIAL PRIMARY KEY,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    smtp_host           VARCHAR(255) NOT NULL DEFAULT 'smtp.gmail.com',
    smtp_port           INTEGER NOT NULL DEFAULT 587,
    smtp_username       VARCHAR(150),
    smtp_password       VARCHAR(255),
    from_address        VARCHAR(150),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by_user_id  BIGINT REFERENCES users(id)
);

-- ============ SEDES Y ESPECIALIDADES ============

CREATE TABLE IF NOT EXISTS branches (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(150) NOT NULL,
    address             VARCHAR(255),
    city                VARCHAR(100),
    phone               VARCHAR(30),
    open_time           TIME,
    close_time          TIME,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id  BIGINT REFERENCES users(id),
    updated_at          TIMESTAMP,
    updated_by_user_id  BIGINT REFERENCES users(id),
    deleted_at          TIMESTAMP,
    deleted_by_user_id  BIGINT REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS specialties (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100) NOT NULL UNIQUE,
    description         VARCHAR(500),
    recommendations     TEXT,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id  BIGINT REFERENCES users(id),
    updated_at          TIMESTAMP,
    updated_by_user_id  BIGINT REFERENCES users(id),
    deleted_at          TIMESTAMP,
    deleted_by_user_id  BIGINT REFERENCES users(id)
);

-- Restriccion opcional de recepcionista por especialidad (sin filas = recepcionista general).
CREATE TABLE IF NOT EXISTS receptionist_specialties (
    user_id         BIGINT NOT NULL REFERENCES users(id),
    specialty_id    BIGINT NOT NULL REFERENCES specialties(id),
    PRIMARY KEY (user_id, specialty_id)
);
CREATE INDEX IF NOT EXISTS idx_receptionist_specialties_specialty_id ON receptionist_specialties(specialty_id);

-- ============ DOCTORES ============

CREATE TABLE IF NOT EXISTS doctors (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL UNIQUE REFERENCES users(id),
    specialty_id            BIGINT NOT NULL REFERENCES specialties(id),
    license_number          VARCHAR(50),
    bio                     TEXT,
    photo_url               VARCHAR(500),
    default_slot_minutes    INTEGER NOT NULL DEFAULT 30,
    prescription_template   TEXT,
    consultation_price      NUMERIC(10,2) CHECK (consultation_price IS NULL OR consultation_price >= 0),
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id      BIGINT REFERENCES users(id),
    updated_at              TIMESTAMP,
    updated_by_user_id      BIGINT REFERENCES users(id),
    deleted_at              TIMESTAMP,
    deleted_by_user_id      BIGINT REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_doctors_specialty_id ON doctors(specialty_id);

CREATE TABLE IF NOT EXISTS doctor_branches (
    doctor_id   BIGINT NOT NULL REFERENCES doctors(id),
    branch_id   BIGINT NOT NULL REFERENCES branches(id),
    PRIMARY KEY (doctor_id, branch_id)
);
CREATE INDEX IF NOT EXISTS idx_doctor_branches_branch_id ON doctor_branches(branch_id);

CREATE TABLE IF NOT EXISTS doctor_schedules (
    id                  BIGSERIAL PRIMARY KEY,
    doctor_id           BIGINT NOT NULL REFERENCES doctors(id),
    branch_id           BIGINT NOT NULL REFERENCES branches(id),
    day_of_week         VARCHAR(15) NOT NULL
                          CHECK (day_of_week IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),
    start_time          TIME NOT NULL,
    end_time            TIME NOT NULL,
    slot_minutes        INTEGER,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id  BIGINT REFERENCES users(id),
    updated_at          TIMESTAMP,
    updated_by_user_id  BIGINT REFERENCES users(id),
    deleted_at          TIMESTAMP,
    deleted_by_user_id  BIGINT REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_doctor_schedules_doctor_id ON doctor_schedules(doctor_id);
CREATE INDEX IF NOT EXISTS idx_doctor_schedules_day ON doctor_schedules(doctor_id, day_of_week);

CREATE TABLE IF NOT EXISTS doctor_schedule_exceptions (
    id                  BIGSERIAL PRIMARY KEY,
    doctor_id           BIGINT NOT NULL REFERENCES doctors(id),
    date                DATE NOT NULL,
    all_day             BOOLEAN NOT NULL DEFAULT TRUE,
    start_time          TIME,
    end_time            TIME,
    reason              TEXT,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id  BIGINT REFERENCES users(id),
    updated_at          TIMESTAMP,
    updated_by_user_id  BIGINT REFERENCES users(id),
    deleted_at          TIMESTAMP,
    deleted_by_user_id  BIGINT REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_doctor_schedule_exceptions_doctor_date ON doctor_schedule_exceptions(doctor_id, date);

-- ============ PACIENTES ============
-- Un paciente puede o no tener cuenta (user_id NULL): los agendados por telefono/recepcion
-- nunca generan una fila en `users`, solo viven aqui.

CREATE TABLE IF NOT EXISTS patients (
    id                              BIGSERIAL PRIMARY KEY,
    first_name                      VARCHAR(100) NOT NULL,
    last_name                       VARCHAR(100) NOT NULL,
    phone                           VARCHAR(30) NOT NULL,
    email                           VARCHAR(150),
    user_id                         BIGINT REFERENCES users(id),
    allergies                       VARCHAR(150),
    blood_type                      VARCHAR(20),
    blood_type_other                VARCHAR(50),
    medical_info_updated_by_user_id BIGINT REFERENCES users(id),
    medical_info_updated_at         TIMESTAMP,
    is_active                       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id              BIGINT REFERENCES users(id),
    updated_at                      TIMESTAMP,
    updated_by_user_id              BIGINT REFERENCES users(id),
    deleted_at                      TIMESTAMP,
    deleted_by_user_id              BIGINT REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_patients_phone ON patients(phone);
CREATE INDEX IF NOT EXISTS idx_patients_user_id ON patients(user_id);

-- ============ CITAS ============

CREATE TABLE IF NOT EXISTS appointments (
    id                          BIGSERIAL PRIMARY KEY,
    doctor_id                   BIGINT NOT NULL REFERENCES doctors(id),
    branch_id                   BIGINT NOT NULL REFERENCES branches(id),
    patient_id                  BIGINT NOT NULL REFERENCES patients(id),
    appointment_date            DATE NOT NULL,
    start_time                  TIME NOT NULL,
    end_time                    TIME NOT NULL,
    status                      VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED'
                                  CHECK (status IN ('SCHEDULED','CANCELLED','COMPLETED','NO_SHOW')),
    reason_for_visit            TEXT,
    cancel_reason               TEXT,
    cancelled_by_user_id        BIGINT REFERENCES users(id),
    cancelled_at                TIMESTAMP,
    slot_released               BOOLEAN NOT NULL DEFAULT FALSE,
    release_eligible            BOOLEAN,
    released_by_user_id         BIGINT REFERENCES users(id),
    released_at                 TIMESTAMP,
    -- Recepcionista/admin que agendo la cita por telefono; NULL si el paciente se auto-agendo.
    created_by_user_id_appt     BIGINT REFERENCES users(id),
    cancel_token                UUID NOT NULL UNIQUE,
    -- Alergias/tipo de sangre tal como estaban AL AGENDAR (el dato vigente vive en patients).
    allergies_snapshot          VARCHAR(150),
    blood_type_snapshot         VARCHAR(20),
    blood_type_other_snapshot   VARCHAR(50),
    reminder_2h_sent_at         TIMESTAMP,
    -- Bloqueo optimista, llegada del paciente y sobrecupo (solo recepcion/admin).
    version                     BIGINT NOT NULL DEFAULT 0,
    arrived_at                  TIMESTAMP,
    overbooked                  BOOLEAN NOT NULL DEFAULT FALSE,
    -- Precio de la cita al agendar (foto: no cambia si el doctor cambia su precio) y su pago.
    price                       NUMERIC(10,2),
    payment_status              VARCHAR(15) NOT NULL DEFAULT 'UNPAID'
                                  CHECK (payment_status IN ('UNPAID', 'PENDING', 'PAID', 'REFUNDED')),
    is_active                   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id          BIGINT REFERENCES users(id),
    updated_at                  TIMESTAMP,
    updated_by_user_id          BIGINT REFERENCES users(id),
    deleted_at                  TIMESTAMP,
    deleted_by_user_id          BIGINT REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_appointments_doctor_id ON appointments(doctor_id);
CREATE INDEX IF NOT EXISTS idx_appointments_date ON appointments(appointment_date);
CREATE INDEX IF NOT EXISTS idx_appointments_patient_id ON appointments(patient_id);
CREATE INDEX IF NOT EXISTS idx_appointments_status ON appointments(status);
CREATE INDEX IF NOT EXISTS idx_appointments_doctor_date_time ON appointments (doctor_id, appointment_date, start_time);
CREATE INDEX IF NOT EXISTS idx_appointments_branch_id ON appointments (branch_id);

-- Defensa contra doble reserva concurrente (el service ademas toma un bloqueo pesimista).
-- El sobrecupo queda fuera de ambas reglas; el resto no puede repetir horario ni traslaparse.
CREATE UNIQUE INDEX IF NOT EXISTS ux_appointments_slot
    ON appointments(doctor_id, appointment_date, start_time)
    WHERE status <> 'CANCELLED' AND overbooked = FALSE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ex_appointments_no_overlap') THEN
        ALTER TABLE appointments ADD CONSTRAINT ex_appointments_no_overlap
            EXCLUDE USING gist (
                doctor_id WITH =,
                tsrange(appointment_date + start_time, appointment_date + end_time) WITH &&
            ) WHERE (status <> 'CANCELLED' AND overbooked = FALSE);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS appointment_status_history (
    id                  BIGSERIAL PRIMARY KEY,
    appointment_id      BIGINT NOT NULL REFERENCES appointments(id),
    from_status         VARCHAR(20),
    to_status           VARCHAR(20) NOT NULL,
    changed_by_user_id  BIGINT REFERENCES users(id),
    changed_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    note                TEXT
);
CREATE INDEX IF NOT EXISTS idx_appointment_status_history_appointment_id ON appointment_status_history(appointment_id);

-- ============ NOTIFICACIONES EN APP ============

CREATE TABLE IF NOT EXISTS notifications (
    id              BIGSERIAL PRIMARY KEY,
    recipient_id    BIGINT NOT NULL REFERENCES users(id),
    type            VARCHAR(30) NOT NULL
                      CHECK (type IN ('NEW_APPOINTMENT', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_RESCHEDULED', 'SCHEDULE_CONFLICT')),
    message         VARCHAR(500) NOT NULL,
    appointment_id  BIGINT REFERENCES appointments(id),
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON notifications(recipient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_unread ON notifications(recipient_id, is_read) WHERE is_read = FALSE;

-- ============ RECETAS ============

CREATE TABLE IF NOT EXISTS prescriptions (
    id                  BIGSERIAL PRIMARY KEY,
    appointment_id      BIGINT NOT NULL REFERENCES appointments(id),
    diagnosis           TEXT,
    content             TEXT NOT NULL,
    voided_at           TIMESTAMP,
    voided_by_user_id   BIGINT REFERENCES users(id),
    void_reason         VARCHAR(500),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id  BIGINT REFERENCES users(id),
    updated_by_user_id  BIGINT REFERENCES users(id),
    deleted_by_user_id  BIGINT REFERENCES users(id),
    deleted_at          TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_prescriptions_appointment_id ON prescriptions(appointment_id);

-- ============ CORTES DE CAJA Y PAGOS ============

CREATE TABLE IF NOT EXISTS cash_cuts (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES users(id),
    status                  VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    opened_at               TIMESTAMP NOT NULL DEFAULT NOW(),
    opening_amount          NUMERIC(12,2) NOT NULL DEFAULT 0,
    opening_notes           TEXT,
    closed_at               TIMESTAMP,
    closed_by_user_id       BIGINT REFERENCES users(id),
    -- Foto de los totales al cerrar (mientras esta abierto se calculan en vivo).
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
    -- Reembolso: NONE, REFUNDED (ya se devolvio) o REVIEW (a consideracion del administrador).
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

-- ============================================================
-- DATOS INICIALES
-- ============================================================

INSERT INTO roles (name) VALUES ('ADMIN') ON CONFLICT (name) DO NOTHING;
INSERT INTO roles (name) VALUES ('RECEPTIONIST') ON CONFLICT (name) DO NOTHING;
INSERT INTO roles (name) VALUES ('DOCTOR') ON CONFLICT (name) DO NOTHING;
INSERT INTO roles (name) VALUES ('PATIENT') ON CONFLICT (name) DO NOTHING;

INSERT INTO hospital_config (name, primary_color, description, contact_email)
SELECT 'Hospital Central', '#0F766E', 'Atencion medica de calidad, cerca de ti.', 'ing.arturopineda.94@gmail.com'
WHERE NOT EXISTS (SELECT 1 FROM hospital_config);

-- Sin credenciales SMTP a proposito: el admin las captura desde el panel una vez desplegado.
INSERT INTO email_config (enabled, smtp_host, smtp_port)
SELECT FALSE, 'smtp.gmail.com', 587
WHERE NOT EXISTS (SELECT 1 FROM email_config);

-- Usuario ADMIN. password_hash es un BCrypt (strength 10) de la contrasena inicial; no queda
-- la contrasena en texto plano en este archivo. Se recomienda cambiarla desde
-- "Cambiar contrasena" tras el primer ingreso.
INSERT INTO users (email, password_hash, first_name, last_name, role_id, is_active)
SELECT 'ing.arturopineda.94@gmail.com',
       '$2a$10$rEWElUDUc7LjG2D2kGIFdubiqwukEqGa6O/6VF8fn3x1QmyiS1apO',
       'Arturo', 'Pineda', r.id, TRUE
FROM roles r WHERE r.name = 'ADMIN'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'ing.arturopineda.94@gmail.com');
