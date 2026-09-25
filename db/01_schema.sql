-- ============================================================
-- Hospital Citas - Esquema de base de datos PostgreSQL
-- Convenciones: snake_case, BIGSERIAL PK, FKs <tabla>_id,
-- auditoria (created_at/by, updated_at/by, deleted_at/by, is_active),
-- enums como VARCHAR + CHECK (mismo patron que libreria-backend / DemoPV-Backend)
-- ============================================================

-- ============ ROLES Y USUARIOS ============

CREATE TABLE IF NOT EXISTS roles (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(30) NOT NULL UNIQUE CHECK (name IN ('ADMIN', 'RECEPTIONIST', 'DOCTOR', 'PATIENT')),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS users (
    id                  BIGSERIAL PRIMARY KEY,
    email               VARCHAR(150) NOT NULL UNIQUE,
    password_hash       VARCHAR(255) NOT NULL,
    first_name          VARCHAR(100) NOT NULL,
    last_name           VARCHAR(100) NOT NULL,
    phone               VARCHAR(30),
    role_id             BIGINT NOT NULL REFERENCES roles(id),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id  BIGINT REFERENCES users(id),
    updated_at          TIMESTAMP,
    updated_by_user_id  BIGINT REFERENCES users(id),
    deleted_at          TIMESTAMP,
    deleted_by_user_id  BIGINT REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_role_id ON users(role_id);

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
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id  BIGINT REFERENCES users(id),
    updated_at          TIMESTAMP,
    updated_by_user_id  BIGINT REFERENCES users(id),
    deleted_at          TIMESTAMP,
    deleted_by_user_id  BIGINT REFERENCES users(id)
);

-- ============ DOCTORES ============

CREATE TABLE IF NOT EXISTS doctors (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL UNIQUE REFERENCES users(id),
    specialty_id            BIGINT NOT NULL REFERENCES specialties(id),
    license_number          VARCHAR(50),
    bio                     TEXT,
    photo_url               VARCHAR(500),
    default_slot_minutes    INTEGER NOT NULL DEFAULT 30,
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
-- NUNCA generan una fila en `users`, solo viven aqui.

CREATE TABLE IF NOT EXISTS patients (
    id                  BIGSERIAL PRIMARY KEY,
    first_name          VARCHAR(100) NOT NULL,
    last_name           VARCHAR(100) NOT NULL,
    phone               VARCHAR(30) NOT NULL,
    email               VARCHAR(150),
    user_id             BIGINT REFERENCES users(id),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id  BIGINT REFERENCES users(id),
    updated_at          TIMESTAMP,
    updated_by_user_id  BIGINT REFERENCES users(id),
    deleted_at          TIMESTAMP,
    deleted_by_user_id  BIGINT REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_patients_phone ON patients(phone);
CREATE INDEX IF NOT EXISTS idx_patients_user_id ON patients(user_id);

-- ============ CITAS ============
-- Regla de disponibilidad (ver AvailabilityService/AppointmentService): un slot
-- (doctor_id, appointment_date, start_time) esta OCUPADO si existe una cita con
-- status IN ('SCHEDULED','COMPLETED','NO_SHOW'), o CANCELLED con slot_released=false;
-- disponible en cualquier otro caso (sin fila, o CANCELLED con slot_released=true).

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
    -- Recepcionista/admin que agendo la cita por telefono; NULL si el paciente se auto-agendo.
    created_by_user_id_appt     BIGINT REFERENCES users(id),
    cancel_token                UUID NOT NULL UNIQUE,
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

-- Segunda linea de defensa contra doble-reserva concurrente (ver AppointmentServiceImpl,
-- que ademas toma un bloqueo pesimista sobre cualquier fila ocupante existente antes de
-- insertar). No reemplaza la regla completa de negocio (que tambien excluye CANCELLED con
-- slot_released=true) -- eso lo valida siempre el service antes de insertar.
CREATE UNIQUE INDEX IF NOT EXISTS ux_appointments_slot
    ON appointments(doctor_id, appointment_date, start_time) WHERE status <> 'CANCELLED';

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

-- ============ CONFIGURACION DE CORREO (SMTP) ============
-- Fila unica, editable desde /api/admin/email-config. La contrasena NUNCA se devuelve
-- al frontend (solo un booleano "passwordConfigured"); se guarda en texto plano aqui
-- porque el propio Backend la necesita para autenticarse contra el SMTP en cada envio.
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
