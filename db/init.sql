-- ============================================================
-- Hospital Citas - init.sql
-- Base de datos NUEVA y lista para usar: esquema completo (equivale a 01_schema.sql + las
-- migraciones 03 a 20 ya aplicadas; la 20 -- multi-hospital -- va al final de este archivo) + datos minimos para poder entrar al sistema:
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
-- migraciones incrementales que le falten (db/03_*.sql ... db/20_*.sql).
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

-- ============================================================
-- MULTI-HOSPITAL (idéntico a db/20_multi_hospital.sql): lo de arriba queda como el hospital 1
-- (/c/consultorio-1) y se crea el usuario SUPER_ADMIN.
-- ============================================================

BEGIN;

-- ============ HOSPITALES ============

CREATE TABLE IF NOT EXISTS hospitals (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(150) NOT NULL,
    slug                 VARCHAR(60)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Cuenta de OpenPay del hospital (el dinero le llega directo). Sin llaves: solo cobra en recepción.
    openpay_merchant_id  VARCHAR(100),
    openpay_private_key  VARCHAR(100),
    openpay_public_key   VARCHAR(100),
    openpay_production   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP
);

-- Hospital 1: todo lo que ya existía. Toma el nombre que el admin tenía configurado.
INSERT INTO hospitals (id, name, slug)
SELECT 1, COALESCE((SELECT name FROM hospital_config ORDER BY id LIMIT 1), 'Consultorio 1'), 'consultorio-1'
WHERE NOT EXISTS (SELECT 1 FROM hospitals WHERE id = 1);
SELECT setval('hospitals_id_seq', GREATEST((SELECT MAX(id) FROM hospitals), 1));

-- ============ hospital_id EN CADA TABLA DE NEGOCIO ============

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'users', 'branches', 'specialties', 'doctors', 'doctor_schedules', 'doctor_schedule_exceptions',
        'patients', 'appointments', 'appointment_status_history', 'notifications', 'prescriptions',
        'cash_cuts', 'payments', 'hospital_config', 'email_config'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS hospital_id BIGINT REFERENCES hospitals(id)', t);
        IF t = 'users' THEN
            -- El SUPER_ADMIN se queda sin hospital (también en una segunda corrida).
            UPDATE users u SET hospital_id = 1
            WHERE u.hospital_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM roles r WHERE r.id = u.role_id AND r.name = 'SUPER_ADMIN');
        ELSE
            EXECUTE format('UPDATE %I SET hospital_id = 1 WHERE hospital_id IS NULL', t);
        END IF;
        -- users queda NULLABLE: el SUPER_ADMIN no pertenece a ningún hospital.
        IF t <> 'users' THEN
            EXECUTE format('ALTER TABLE %I ALTER COLUMN hospital_id SET NOT NULL', t);
        END IF;
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I(hospital_id)', 'idx_' || t || '_hospital_id', t);
    END LOOP;
END $$;

-- Un solo renglón de configuración (marca, SMTP) por hospital.
CREATE UNIQUE INDEX IF NOT EXISTS ux_hospital_config_hospital ON hospital_config(hospital_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_email_config_hospital ON email_config(hospital_id);

-- ============ UNICIDAD POR HOSPITAL ============

-- Correo: único dentro de cada hospital; entre usuarios de plataforma (sin hospital), único global.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_hospital_email ON users(hospital_id, email) WHERE hospital_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_platform_email ON users(email) WHERE hospital_id IS NULL;

-- Especialidades: el nombre se repite entre hospitales (cada uno tiene su "Pediatría").
ALTER TABLE specialties DROP CONSTRAINT IF EXISTS specialties_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS ux_specialties_hospital_name ON specialties(hospital_id, name);

-- ============ SUPER ADMIN ============

ALTER TABLE roles DROP CONSTRAINT IF EXISTS roles_name_check;
ALTER TABLE roles ADD CONSTRAINT roles_name_check
    CHECK (name IN ('SUPER_ADMIN', 'ADMIN', 'RECEPTIONIST', 'DOCTOR', 'PATIENT'));
INSERT INTO roles (name) VALUES ('SUPER_ADMIN') ON CONFLICT (name) DO NOTHING;

-- Usuario de plataforma (Nexora). password_hash es un BCrypt de una contraseña temporal
-- (no queda en texto plano aquí); se pide cambiarla en el primer ingreso. Entra en la raíz del
-- sitio (/login), no en el link de un hospital.
INSERT INTO users (email, password_hash, first_name, last_name, role_id, is_active, must_change_password, hospital_id)
SELECT 'ing.arturopineda.94@gmail.com',
       '$2a$10$UKPROg2pWzf2VPm24Blq2OAZD3CqFMANRddSWWkInsRNMtt/KBHMm',
       'Super', 'Admin', r.id, TRUE, TRUE, NULL
FROM roles r WHERE r.name = 'SUPER_ADMIN'
AND NOT EXISTS (SELECT 1 FROM users WHERE hospital_id IS NULL AND email = 'ing.arturopineda.94@gmail.com');

COMMIT;

-- ============================================================
-- 21 (idéntico a db/21_apartados_arco_seguridad.sql): apartado temporal de horarios,
-- solicitudes ARCO y cifrado de credenciales.
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

-- ============================================================
-- 22 (idéntico a db/22_bitacora_y_rate_limit.sql): bitácora y rate limiting compartido.
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

-- ============================================================
-- 23_datos_direccion (idéntico a db/23_datos_direccion.sql)
-- ============================================================
BEGIN;

-- Hospital: datos fiscales y dirección (aparecen en comprobantes, recetas y correos).
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS legal_name    VARCHAR(200);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS rfc           VARCHAR(13);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS address_line  VARCHAR(200);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS neighborhood  VARCHAR(120);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS city          VARCHAR(100);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS state         VARCHAR(80);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS postal_code   VARCHAR(5);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS website       VARCHAR(200);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS whatsapp      VARCHAR(10);
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS opening_hours VARCHAR(300);

-- Sedes: colonia, estado y código postal.
ALTER TABLE branches ADD COLUMN IF NOT EXISTS neighborhood VARCHAR(120);
ALTER TABLE branches ADD COLUMN IF NOT EXISTS state        VARCHAR(80);
ALTER TABLE branches ADD COLUMN IF NOT EXISTS postal_code  VARCHAR(5);

-- Pacientes: datos personales y de contacto.
ALTER TABLE patients ADD COLUMN IF NOT EXISTS birth_date              DATE;
ALTER TABLE patients ADD COLUMN IF NOT EXISTS sex                     VARCHAR(1);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS curp                    VARCHAR(18);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS address_line            VARCHAR(200);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS neighborhood            VARCHAR(120);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS city                    VARCHAR(100);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS state                   VARCHAR(80);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS postal_code             VARCHAR(5);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS emergency_contact_name  VARCHAR(150);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS emergency_contact_phone VARCHAR(10);

COMMIT;

-- ============================================================
-- 24_permisos (idéntico a db/24_permisos.sql)
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

-- 25 · Citas cuya hora ya pasó sin que nadie confirmara si el paciente llegó.
-- Antes se marcaban SIEMPRE como "no asistió" 15 minutos después de terminar. Ahora cada
-- hospital decide: por defecto queda apagado y recepción las confirma en "Cobrar"
-- ("Confirma si llegó": Llegó → Cobrar / No asistió).
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS auto_no_show_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE hospital_config ADD COLUMN IF NOT EXISTS no_show_grace_minutes INTEGER NOT NULL DEFAULT 30;

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
