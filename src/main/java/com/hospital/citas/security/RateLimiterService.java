package com.hospital.citas.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Token bucket en memoria, una instancia por clave (endpoint + IP) -- suficiente para un
 * despliegue de una sola instancia (ver RateLimitInterceptor), sin depender de Redis. Los
 * buckets nunca se limpian explícitamente: en la práctica son pocos miles de IPs distintas
 * como mucho, un costo de memoria despreciable frente a lo que evita (abuso de endpoints
 * públicos sin autenticación).
 */
@Component
public class RateLimiterService {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean tryConsume(String key, int capacity, Duration window) {
        Bucket bucket = buckets.computeIfAbsent(key, newBucket(capacity, window));
        return bucket.tryConsume(1);
    }

    private Function<String, Bucket> newBucket(int capacity, Duration window) {
        Bandwidth limit = Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build();
        return key -> Bucket.builder().addLimit(limit).build();
    }
}
