INSERT INTO roles (name) VALUES ('SUPER_ADMIN');
INSERT INTO roles (name) VALUES ('ADMIN');
INSERT INTO roles (name) VALUES ('RECEPTIONIST');
INSERT INTO roles (name) VALUES ('DOCTOR');
INSERT INTO roles (name) VALUES ('PATIENT');
-- Hospital de las pruebas: todo lo que crean los tests vive aquí (ver AbstractIntegrationTest).
INSERT INTO hospitals (name, slug, is_active, openpay_production, created_at) VALUES ('Hospital de pruebas', 'test', TRUE, FALSE, CURRENT_TIMESTAMP);

-- Permisos configurables (tablas sin entidad: las lee PermissionService con JDBC).
CREATE TABLE IF NOT EXISTS role_permissions (
    hospital_id BIGINT NOT NULL, role VARCHAR(30) NOT NULL, permission VARCHAR(60) NOT NULL, granted BOOLEAN NOT NULL,
    PRIMARY KEY (hospital_id, role, permission));
CREATE TABLE IF NOT EXISTS user_permission_overrides (
    hospital_id BIGINT NOT NULL, user_id BIGINT NOT NULL, permission VARCHAR(60) NOT NULL, effect VARCHAR(5) NOT NULL,
    PRIMARY KEY (user_id, permission));
