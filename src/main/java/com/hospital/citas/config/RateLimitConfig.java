package com.hospital.citas.config;

import com.hospital.citas.security.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Desactivado en el perfil "test" (ver application-test.properties): las pruebas de
 * integración disparan varias reservas de invitado en segundos desde la misma IP de loopback,
 * que es exactamente el patrón que este límite está diseñado para bloquear en producción. */
@Configuration
@Profile("!test")
@RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor);
    }
}
