package com.hospital.citas.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Contadores de rate limiting guardados en Postgres (tabla {@code rate_limit_hits}), para cuando
 * el backend corre en VARIAS instancias: con contadores en memoria cada instancia llevaría los
 * suyos y el límite real se multiplicaría por el número de instancias. Se activa con
 * {@code APP_RATE_LIMIT_STORE=database}; con una sola instancia basta el de memoria (más rápido).
 * <p>
 * Ventana fija: cada clave (regla + IP) cuenta sus peticiones dentro de bloques de {@code window};
 * un solo UPSERT atómico suma y devuelve el total, así dos instancias nunca se pisan.
 * Usa JDBC directo a propósito: estos contadores no pertenecen a ningún hospital.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "database")
@RequiredArgsConstructor
public class DatabaseRateLimitStore {

    private static final String UPSERT = """
            INSERT INTO rate_limit_hits (bucket_key, window_start, hits) VALUES (?, ?, 1)
            ON CONFLICT (bucket_key, window_start) DO UPDATE SET hits = rate_limit_hits.hits + 1
            RETURNING hits
            """;

    private final JdbcTemplate jdbc;

    /** @return 0 si se permite; si no, los segundos que faltan para que empiece la siguiente ventana. */
    public long secondsToWait(String key, int capacity, Duration window) {
        long windowMillis = window.toMillis();
        long now = Instant.now().toEpochMilli();
        long windowStart = now - (now % windowMillis);
        Integer hits = jdbc.queryForObject(UPSERT, Integer.class, key, new Timestamp(windowStart));
        if (hits != null && hits <= capacity) {
            return 0;
        }
        return Math.max(1, (windowStart + windowMillis - now + 999) / 1000);
    }

    /** Cada hora se borran las ventanas viejas (la más larga es de 15 minutos). */
    @Scheduled(fixedRate = 3_600_000)
    void purge() {
        jdbc.update("DELETE FROM rate_limit_hits WHERE window_start < ?", new Timestamp(System.currentTimeMillis() - 86_400_000L));
    }
}
