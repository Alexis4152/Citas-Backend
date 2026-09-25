-- Protección contra fuerza bruta en el login (hallazgo de seguridad "Alto" de la auditoría):
-- cuenta intentos fallidos consecutivos por cuenta y la bloquea temporalmente al llegar al
-- límite (ver AuthServiceImpl#login / User#isAccountNonLocked).
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;
