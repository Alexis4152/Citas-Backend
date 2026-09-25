package com.hospital.citas;

import com.hospital.citas.security.RateLimiterService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Prueba unitaria pura (sin contexto de Spring) del token bucket que respalda
 * RateLimitInterceptor -- ver por qué el propio interceptor está desactivado en el perfil de
 * pruebas de integración (RateLimitConfig). */
class RateLimiterServiceTest {

    @Test
    void allowsUpToCapacityThenBlocksWithinTheSameWindow() {
        RateLimiterService service = new RateLimiterService();
        String key = "test-key-" + System.nanoTime();

        for (int i = 0; i < 3; i++) {
            assertThat(service.tryConsume(key, 3, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(service.tryConsume(key, 3, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void differentKeysHaveIndependentBuckets() {
        RateLimiterService service = new RateLimiterService();
        String keyA = "key-a-" + System.nanoTime();
        String keyB = "key-b-" + System.nanoTime();

        assertThat(service.tryConsume(keyA, 1, Duration.ofMinutes(1))).isTrue();
        assertThat(service.tryConsume(keyA, 1, Duration.ofMinutes(1))).isFalse();
        // Otra IP/endpoint no se ve afectada por el consumo de la primera clave.
        assertThat(service.tryConsume(keyB, 1, Duration.ofMinutes(1))).isTrue();
    }
}
