package com.hospital.citas.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Revisa al arrancar la configuración sensible (patrón ProductionReadinessValidator de
 * nexora-hotel). Si el frontend está en https (producción) y algo es inseguro, lo deja en el log
 * como ERROR; con {@code APP_SECURITY_STRICT=true} además detiene el arranque, para que un
 * despliegue nuevo no pueda salir con un secreto de ejemplo.
 */
@Component
public class SecurityReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(SecurityReadinessCheck.class);
    private static final List<String> WEAK_MARKERS = List.of("change-in-production", "changeme", "dev-secret",
            "example", "ejemplo", "test-secret", "placeholder");

    @Value("${app.jwt.secret}")
    private String jwtSecret;
    @Value("${app.encryption.key:}")
    private String encryptionKey;
    @Value("${app.cookie.secure}")
    private boolean cookieSecure;
    @Value("${app.frontend-url}")
    private String frontendUrl;
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;
    @Value("${app.security.strict:false}")
    private boolean strict;

    @PostConstruct
    void check() {
        boolean production = frontendUrl != null && frontendUrl.startsWith("https://");
        if (!production) {
            return;
        }
        List<String> problems = new ArrayList<>();
        String secret = jwtSecret.toLowerCase(Locale.ROOT);
        if (WEAK_MARKERS.stream().anyMatch(secret::contains) || jwtSecret.length() < 32) {
            problems.add("APP_JWT_SECRET parece un valor de ejemplo o es muy corto (genera uno: openssl rand -base64 48)");
        }
        if (encryptionKey == null || encryptionKey.isBlank()) {
            problems.add("APP_ENCRYPTION_KEY no está definida (cifra las llaves de OpenPay y la contraseña SMTP)");
        } else if (encryptionKey.equals(jwtSecret)) {
            problems.add("APP_ENCRYPTION_KEY y APP_JWT_SECRET deben ser distintos");
        }
        if (!cookieSecure) {
            problems.add("APP_COOKIE_SECURE debe ser true con https");
        }
        for (String origin : allowedOrigins.split(",")) {
            String o = origin.trim().toLowerCase(Locale.ROOT);
            if (!o.isEmpty() && (!o.startsWith("https://") || o.contains("localhost") || o.contains("127.0.0.1"))) {
                problems.add("APP_CORS_ALLOWED_ORIGINS solo debe tener orígenes https públicos: " + origin.trim());
            }
        }
        if (problems.isEmpty()) {
            return;
        }
        String message = "Configuración de seguridad a revisar:\n - " + String.join("\n - ", problems);
        if (strict) {
            throw new IllegalStateException(message);
        }
        log.error(message);
    }
}
