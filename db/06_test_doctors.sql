-- ============================================================
-- Hospital Citas - 20 doctores adicionales para pruebas reales
-- (disponibilidad, filtros de busqueda, paginacion, agendado en fin de semana, etc.)
--
-- Password para los 20 (BCrypt, mismo hash que los doctores demo de 02_seed.sql):
--   Doctor123!
--
-- Distribucion pensada para variedad de pruebas:
--   - 5 doctores por cada una de las 4 especialidades existentes (misma especialidad,
--     doctores distintos -> probar que el buscador de "Buscar doctor" y el filtro por
--     especialidad devuelван varios resultados).
--   - Horarios con rangos y duracion de slot distintos (15/20/30/45 min).
--   - Seis doctores trabajan tambien SABADO (torres, castro, vargas, aguilar, jimenez, guzman).
--   - Tres doctores trabajan DOMINGO (morales, jimenez, campos) -- jimenez es un caso
--     "solo fines de semana" (unicamente sabado y domingo, sin entre semana).
--   - Dos doctores atienden en AMBAS sedes con dias distintos por sede (mendoza, salazar).
--
-- Script idempotente (mismo patron que 02_seed.sql): se puede correr varias veces sin
-- duplicar filas.
-- ============================================================

-- ============ MEDICINA GENERAL ============

-- 1) Roberto Gomez - Sede Centro - Lun-Vie 08:00-14:00 - slot 30
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.gomez@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Roberto', 'Gomez', '5555561001', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.gomez@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T01', 'Medico general de prueba (QA) - turno matutino entre semana.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.gomez@hospital-demo.com' AND s.name = 'Medicina General'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email = 'doctor.gomez@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '08:00', '14:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.gomez@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 2) Patricia Sanchez - Sede Centro - Lun-Vie 14:00-20:00 - slot 20
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.sanchez@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Patricia', 'Sanchez', '5555561002', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.sanchez@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T02', 'Medica general de prueba (QA) - turno vespertino entre semana.', 20
FROM users u, specialties s
WHERE u.email = 'doctor.sanchez@hospital-demo.com' AND s.name = 'Medicina General'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email = 'doctor.sanchez@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '14:00', '20:00', 20
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.sanchez@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 3) Fernando Torres - Sede Norte - Lun-Sab 09:00-15:00 - slot 30 (trabaja SABADO)
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.torres@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Fernando', 'Torres', '5555561003', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.torres@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T03', 'Medico general de prueba (QA) - incluye sabados.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.torres@hospital-demo.com' AND s.name = 'Medicina General'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
WHERE u.email = 'doctor.torres@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '09:00', '15:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'), ('SATURDAY')) AS dow(day)
WHERE u.email = 'doctor.torres@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 4) Silvia Castro - Sede Norte - Mar-Sab 10:00-16:00 - slot 15 (trabaja SABADO)
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.castro@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Silvia', 'Castro', '5555561004', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.castro@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T04', 'Medica general de prueba (QA) - citas cortas de 15 min, incluye sabado.', 15
FROM users u, specialties s
WHERE u.email = 'doctor.castro@hospital-demo.com' AND s.name = 'Medicina General'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
WHERE u.email = 'doctor.castro@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '10:00', '16:00', 15
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
CROSS JOIN (VALUES ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'), ('SATURDAY')) AS dow(day)
WHERE u.email = 'doctor.castro@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 5) Ricardo Mendoza - AMBAS sedes, dias distintos por sede - slot 45
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.mendoza@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Ricardo', 'Mendoza', '5555561005', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.mendoza@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T05', 'Medico general de prueba (QA) - atiende en ambas sedes, dias alternados.', 45
FROM users u, specialties s
WHERE u.email = 'doctor.mendoza@hospital-demo.com' AND s.name = 'Medicina General'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name IN ('Sede Centro', 'Sede Norte')
WHERE u.email = 'doctor.mendoza@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '08:00', '13:00', 45
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('WEDNESDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.mendoza@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '08:00', '13:00', 45
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
CROSS JOIN (VALUES ('TUESDAY'), ('THURSDAY')) AS dow(day)
WHERE u.email = 'doctor.mendoza@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- ============ PEDIATRIA ============

-- 6) Gabriela Ortiz - Sede Centro - Lun-Vie 09:00-14:00 - slot 30
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.ortiz@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Gabriela', 'Ortiz', '5555561006', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.ortiz@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T06', 'Pediatra de prueba (QA) - turno matutino entre semana.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.ortiz@hospital-demo.com' AND s.name = 'Pediatria'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email = 'doctor.ortiz@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '09:00', '14:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.ortiz@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 7) Miguel Angel Ruiz - Sede Norte - Lun-Vie 15:00-19:00 - slot 20
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.ruiz@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Miguel Angel', 'Ruiz', '5555561007', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.ruiz@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T07', 'Pediatra de prueba (QA) - turno vespertino entre semana.', 20
FROM users u, specialties s
WHERE u.email = 'doctor.ruiz@hospital-demo.com' AND s.name = 'Pediatria'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
WHERE u.email = 'doctor.ruiz@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '15:00', '19:00', 20
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.ruiz@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 8) Lucia Vargas - Sede Centro - Lun-Sab 08:00-13:00 - slot 30 (trabaja SABADO)
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.vargas@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Lucia', 'Vargas', '5555561008', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.vargas@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T08', 'Pediatra de prueba (QA) - incluye sabado.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.vargas@hospital-demo.com' AND s.name = 'Pediatria'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email = 'doctor.vargas@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '08:00', '13:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'), ('SATURDAY')) AS dow(day)
WHERE u.email = 'doctor.vargas@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 9) Javier Morales - Sede Norte - Mie-Dom 10:00-14:00 - slot 30 (trabaja DOMINGO)
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.morales@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Javier', 'Morales', '5555561009', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.morales@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T09', 'Pediatra de prueba (QA) - incluye domingo.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.morales@hospital-demo.com' AND s.name = 'Pediatria'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
WHERE u.email = 'doctor.morales@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '10:00', '14:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
CROSS JOIN (VALUES ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'), ('SATURDAY'), ('SUNDAY')) AS dow(day)
WHERE u.email = 'doctor.morales@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 10) Elena Reyes - Sede Centro - Lun,Mar,Jue,Vie 07:00-12:00 - slot 15
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.reyes@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Elena', 'Reyes', '5555561010', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.reyes@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T10', 'Pediatra de prueba (QA) - citas cortas de 15 min, sin miercoles.', 15
FROM users u, specialties s
WHERE u.email = 'doctor.reyes@hospital-demo.com' AND s.name = 'Pediatria'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email = 'doctor.reyes@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '07:00', '12:00', 15
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.reyes@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- ============ GINECOLOGIA ============

-- 11) Alejandro Diaz - Sede Centro - Lun-Vie 10:00-16:00 - slot 30
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.diaz@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Alejandro', 'Diaz', '5555561011', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.diaz@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T11', 'Ginecologo de prueba (QA) - turno entre semana.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.diaz@hospital-demo.com' AND s.name = 'Ginecologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email = 'doctor.diaz@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '10:00', '16:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.diaz@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 12) Monica Herrera - Sede Norte - Lun-Vie 08:00-13:00 - slot 30
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.herrera@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Monica', 'Herrera', '5555561012', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.herrera@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T12', 'Ginecologa de prueba (QA) - turno matutino entre semana.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.herrera@hospital-demo.com' AND s.name = 'Ginecologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
WHERE u.email = 'doctor.herrera@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '08:00', '13:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.herrera@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 13) Sergio Aguilar - Sede Centro - Lun-Sab 12:00-18:00 - slot 20 (trabaja SABADO)
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.aguilar@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Sergio', 'Aguilar', '5555561013', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.aguilar@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T13', 'Ginecologo de prueba (QA) - incluye sabado.', 20
FROM users u, specialties s
WHERE u.email = 'doctor.aguilar@hospital-demo.com' AND s.name = 'Ginecologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email = 'doctor.aguilar@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '12:00', '18:00', 20
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'), ('SATURDAY')) AS dow(day)
WHERE u.email = 'doctor.aguilar@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 14) Karla Jimenez - Sede Norte - SOLO Sab-Dom 09:00-14:00 - slot 30 (fin de semana unicamente)
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.jimenez@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Karla', 'Jimenez', '5555561014', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.jimenez@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T14', 'Ginecologa de prueba (QA) - unicamente fines de semana.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.jimenez@hospital-demo.com' AND s.name = 'Ginecologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
WHERE u.email = 'doctor.jimenez@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '09:00', '14:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
CROSS JOIN (VALUES ('SATURDAY'), ('SUNDAY')) AS dow(day)
WHERE u.email = 'doctor.jimenez@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 15) Hector Navarro - Sede Centro - Lun-Vie 16:00-20:00 - slot 45
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.navarro@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Hector', 'Navarro', '5555561015', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.navarro@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T15', 'Ginecologo de prueba (QA) - turno vespertino, citas largas de 45 min.', 45
FROM users u, specialties s
WHERE u.email = 'doctor.navarro@hospital-demo.com' AND s.name = 'Ginecologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email = 'doctor.navarro@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '16:00', '20:00', 45
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.navarro@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- ============ CARDIOLOGIA ============

-- 16) Adriana Flores - Sede Centro - Lun-Vie 08:00-12:00 - slot 30
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.flores@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Adriana', 'Flores', '5555561016', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.flores@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T16', 'Cardiologa de prueba (QA) - turno matutino entre semana.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.flores@hospital-demo.com' AND s.name = 'Cardiologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email = 'doctor.flores@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '08:00', '12:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.flores@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 17) Raul Chavez - Sede Norte - Lun-Vie 13:00-18:00 - slot 30
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.chavez@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Raul', 'Chavez', '5555561017', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.chavez@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T17', 'Cardiologo de prueba (QA) - turno vespertino entre semana.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.chavez@hospital-demo.com' AND s.name = 'Cardiologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
WHERE u.email = 'doctor.chavez@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '13:00', '18:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.chavez@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 18) Beatriz Salazar - AMBAS sedes, dias distintos por sede - slot 20
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.salazar@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Beatriz', 'Salazar', '5555561018', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.salazar@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T18', 'Cardiologa de prueba (QA) - atiende en ambas sedes, dias alternados.', 20
FROM users u, specialties s
WHERE u.email = 'doctor.salazar@hospital-demo.com' AND s.name = 'Cardiologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name IN ('Sede Centro', 'Sede Norte')
WHERE u.email = 'doctor.salazar@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '09:00', '15:00', 20
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('WEDNESDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.salazar@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '09:00', '15:00', 20
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
CROSS JOIN (VALUES ('TUESDAY'), ('THURSDAY')) AS dow(day)
WHERE u.email = 'doctor.salazar@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 19) Ernesto Guzman - Sede Norte - Lun-Sab 07:00-13:00 - slot 30 (trabaja SABADO)
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.guzman@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Ernesto', 'Guzman', '5555561019', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.guzman@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T19', 'Cardiologo de prueba (QA) - incluye sabado, turno muy matutino.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.guzman@hospital-demo.com' AND s.name = 'Cardiologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
WHERE u.email = 'doctor.guzman@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '07:00', '13:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Norte'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'), ('SATURDAY')) AS dow(day)
WHERE u.email = 'doctor.guzman@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

-- 20) Teresa Campos - Sede Centro - Lun-Vie 15:00-19:00 + DOMINGO 08:00-14:00 - slot 30
INSERT INTO users (email, password_hash, first_name, last_name, phone, role_id, is_active)
SELECT 'doctor.campos@hospital-demo.com', '$2a$10$MYZNkca.ppbJDKAXIG4mL.QEJ1YUII/bpIv68OG/9c1fg173EXYoC',
       'Teresa', 'Campos', '5555561020', r.id, TRUE
FROM roles r WHERE r.name = 'DOCTOR'
AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'doctor.campos@hospital-demo.com');

INSERT INTO doctors (user_id, specialty_id, license_number, bio, default_slot_minutes)
SELECT u.id, s.id, 'CED-T20', 'Cardiologa de prueba (QA) - entre semana en vespertino, ademas domingo.', 30
FROM users u, specialties s
WHERE u.email = 'doctor.campos@hospital-demo.com' AND s.name = 'Cardiologia'
AND NOT EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id);

INSERT INTO doctor_branches (doctor_id, branch_id)
SELECT d.id, b.id FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
WHERE u.email = 'doctor.campos@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_branches db WHERE db.doctor_id = d.id AND db.branch_id = b.id);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '15:00', '19:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) AS dow(day)
WHERE u.email = 'doctor.campos@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);

INSERT INTO doctor_schedules (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes)
SELECT d.id, b.id, dow.day, '08:00', '14:00', 30
FROM doctors d JOIN users u ON u.id = d.user_id JOIN branches b ON b.name = 'Sede Centro'
CROSS JOIN (VALUES ('SUNDAY')) AS dow(day)
WHERE u.email = 'doctor.campos@hospital-demo.com'
AND NOT EXISTS (SELECT 1 FROM doctor_schedules s WHERE s.doctor_id = d.id AND s.branch_id = b.id AND s.day_of_week = dow.day);
