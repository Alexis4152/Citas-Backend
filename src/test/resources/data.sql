INSERT INTO roles (name) VALUES ('SUPER_ADMIN');
INSERT INTO roles (name) VALUES ('ADMIN');
INSERT INTO roles (name) VALUES ('RECEPTIONIST');
INSERT INTO roles (name) VALUES ('DOCTOR');
INSERT INTO roles (name) VALUES ('PATIENT');
-- Hospital de las pruebas: todo lo que crean los tests vive aquí (ver AbstractIntegrationTest).
INSERT INTO hospitals (name, slug, is_active, openpay_production, created_at) VALUES ('Hospital de pruebas', 'test', TRUE, FALSE, CURRENT_TIMESTAMP);
