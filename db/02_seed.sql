-- ============================================================
-- Hospital Citas - Datos demo
-- Passwords (BCrypt generado y verificado con Spring Security BCryptPasswordEncoder):
--   admin@hospital-demo.com          Admin123!
--   recepcion@hospital-demo.com      Recepcion123!
--   doctor.pediatria@hospital-demo.com    Doctor123!
--   doctor.gineco@hospital-demo.com       Doctor123!
--   doctor.cardiologia@hospital-demo.com  Doctor123!
--   paciente@demo.com                Paciente123!
-- ============================================================

-- ============ ROLES ============
INSERT INTO roles (name) VALUES ('ADMIN') ON CONFLICT (name) DO NOTHING;
INSERT INTO roles (name) VALUES ('RECEPTIONIST') ON CONFLICT (name) DO NOTHING;
INSERT INTO roles (name) VALUES ('DOCTOR') ON CONFLICT (name) DO NOTHING;
INSERT INTO roles (name) VALUES ('PATIENT') ON CONFLICT (name) DO NOTHING;

-- ============ CONFIGURACION DEL HOSPITAL ============
INSERT INTO hospital_config (name, primary_color, description, contact_phone, contact_email)
SELECT 'Hospital Central', '#0F766E', 'Atencion medica de calidad, cerca de ti.', '5555550000', 'contacto@hospital-demo.com'
WHERE NOT EXISTS (SELECT 1 FROM hospital_config);

-- ============ SEDES ============
INSERT INTO branches (name, address, city, phone, open_time, close_time)
SELECT 'Sede Centro', 'Av. Juarez 100, Col. Centro', 'Ciudad de Mexico', '5555550101', '08:00', '20:00'
WHERE NOT EXISTS (SELECT 1 FROM branches WHERE name = 'Sede Centro');

INSERT INTO branches (name, address, city, phone, open_time, close_time)
SELECT 'Sede Norte', 'Blvd. Manuel Avila Camacho 250, Col. Lindavista', 'Ciudad de Mexico', '5555550202', '08:00', '18:00'
WHERE NOT EXISTS (SELECT 1 FROM branches WHERE name = 'Sede Norte');

-- ============ ESPECIALIDADES ============
INSERT INTO specialties (name, description) VALUES
 ('Medicina General', 'Atencion medica primaria y preventiva'),
 ('Pediatria', 'Atencion medica para ninas y ninos'),
 ('Ginecologia', 'Salud reproductiva y ginecologica de la mujer'),
 ('Cardiologia', 'Diagnostico y tratamiento de enfermedades del corazon')
ON CONFLICT (name) DO NOTHING;

-- ============ USUARIO ADMIN ============
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'admin@hospital-demo.com', '$2a$10$08tUkXNpQVZbHDCfw6MFdenpJ5/wTpUPRqqZfztbzTmQ7pL4Aqnym',
       'Admin', 'Hospital', '5555550001', r.id, TRUE
FROM roles r WHERE r.name = 'ADMIN'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@hospital-demo.com');

-- ============ USUARIO RECEPCIONISTA ============
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'recepcion@hospital-demo.com', '$2a$10$8NPL19Pf4Lz6yssfyzNvx.ZrjWrmHFVHEOERs/WjpG218Y4R8WxCS',
       'Recepcion', 'Hospital', '5555550002', r.id, TRUE
FROM roles r WHERE r.name = 'RECEPTIONIST'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'recepcion@hospital-demo.com');

-- ============ USUARIOS DOCTOR ============
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.pediatria@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Laura', 'Fernandez', '5555550003', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.pediatria@hospital-demo.com');

INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.gineco@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Ana', 'Martinez', '5555550004', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.gineco@hospital-demo.com');

INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.cardiologia@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Carlos', 'Ramirez', '5555550005', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.cardiologia@hospital-demo.com');

-- ============ USUARIO PACIENTE DEMO ============
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'paciente@demo.com', '$2a$10$dObReLzbxrL16dUsYRBmIe6UqOP0SRi5fYSilyKTr.WYQLDSzG13m',
       'Paciente', 'Demo', '5555550006', r.id, TRUE
FROM roles r WHERE r.name = 'PATIENT'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'paciente@demo.com');

-- ============ PERFILES DE DOCTOR ============
INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-100' || u.id, 'Medica pediatra con amplia experiencia en atencion infantil.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.pediatria@hospital-demo.com' AND s.name = 'Pediatria'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-200' || u.id, 'Medica ginecologa especializada en salud reproductiva.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.gineco@hospital-demo.com' AND s.name = 'Ginecologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-300' || u.id, 'Medico cardiologo con enfoque en prevencion cardiovascular.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.cardiologia@hospital-demo.com' AND s.name = 'Cardiologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

-- ============ ASIGNACION DE SEDES A DOCTORES ============
INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id
FROM doctors d
JOIN users u ON u.id = d.user_id
JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email IN ('doctor.pediatria@hospital-demo.com', 'doctor.gineco@hospital-demo.com', 'doctor.cardiologia@hospital-demo.com')
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

-- El cardiologo tambien atiende en Sede Norte, para variedad en las pruebas de disponibilidad.
INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id
FROM doctors d
JOIN users u ON u.id = d.user_id
JOIN branches b ON b.name = 'Sede Norte'
WHERE u.email = 'doctor.cardiologia@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

-- ============ HORARIOS SEMANALES (Lun-Vie 9:00-14:00, slots de 30 min) EN SEDE CENTRO ============
INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '09:00', '14:00', 30
FROM doctors d
JOIN users u ON u.id = d.user_id
JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email IN ('doctor.pediatria@hospital-demo.com', 'doctor.gineco@hospital-demo.com', 'doctor.cardiologia@hospital-demo.com')
AND NOT EXISTS (
    SELECT 1 FROM doctor_schedules s
    WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day
);

-- ============ PACIENTE DEMO (ligado a la cuenta) ============
INSERT INTO patients (first_name, last_name, phone, email, user_id)
SELECT u.first_name, u.last_name, u.phone, u.email, u.id
FROM users u
WHERE u.email = 'paciente@demo.com'
AND NOT EXISTS (SELECT 1 FROM patients p WHERE p.user_id = u.id);

-- ============ CONFIGURACION DE CORREO (fila unica, deshabilitada por default) ============
-- Sin credenciales reales aqui a proposito (este script se versiona en el repo). El ADMIN
-- las captura desde /api/admin/email-config una vez desplegado el sistema.
INSERT INTO email_config (enabled, smtp_host, smtp_port)
SELECT FALSE, 'smtp.gmail.com', 587
WHERE NOT EXISTS (SELECT 1 FROM email_config);
