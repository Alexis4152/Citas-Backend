-- ============================================================
-- 23: datos de dirección, fiscales y personales (hospital, sedes y pacientes).
-- Todo opcional. Idempotente.
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
