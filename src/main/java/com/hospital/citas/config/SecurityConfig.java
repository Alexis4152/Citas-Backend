package com.hospital.citas.config;

import com.hospital.citas.security.CustomUserDetailsService;
import com.hospital.citas.security.JwtAuthFilter;
import com.hospital.citas.security.RestAccessDeniedHandler;
import com.hospital.citas.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * API stateless (JWT), sin CSRF. Los matchers mas especificos (guest booking, cancelacion
 * por token, comprobante de invitado, refresh/forgot/reset de contrasena) se registran ANTES
 * de los catch-all mas generales para que no queden atrapados por una regla mas amplia.
 * <p>
 * {@code authenticationEntryPoint}/{@code accessDeniedHandler} propios (patron 03) dan un
 * cuerpo JSON consistente ({@link com.hospital.citas.dto.ApiResponse}) en 401/403 -- sin
 * ellos Spring Security responde con su formato default porque el filtro actua antes de
 * llegar al {@code GlobalExceptionHandler}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final CustomUserDetailsService userDetailsService;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // Headers de seguridad (patrón de nexora-hotel). La API solo devuelve JSON e
                // imágenes: con CSP "default-src 'none'" ningún archivo servido puede ejecutar
                // scripts aunque alguien lograra subir uno disfrazado de imagen. no-referrer evita
                // que la URL (que puede traer el token de una cita) viaje a sitios externos.
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .contentTypeOptions(c -> { })
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .httpStrictTransportSecurity(t -> t.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .permissionsPolicy(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // Avisos en vivo (SSE): el cierre de la conexión vuelve a pasar por la cadena
                        // como despacho ASYNC; esa petición ya se autorizó al abrirse.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/auth/change-password", "/api/auth/logout", "/api/auth/permissions").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        // Apartado temporal de horario y formulario ARCO: públicos (sin sesión).
                        .requestMatchers(HttpMethod.POST, "/api/public/slot-holds", "/api/public/arco-requests").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/public/slot-holds/*").permitAll()
                        // Portal de seguimiento ARCO del titular: lo protege el enlace secreto que le llegó por correo.
                        .requestMatchers("/api/public/arco/**").permitAll()
                        .requestMatchers("/uploads/**").permitAll()
                        .requestMatchers("/api/appointments/guest").permitAll()
                        .requestMatchers("/api/appointments/guest-receipt/**").permitAll()
                        .requestMatchers("/api/appointments/guest-lookup").permitAll()
                        .requestMatchers("/api/appointments/cancel/**").permitAll()
                        .requestMatchers("/api/appointments/reschedule/**").permitAll()
                        .requestMatchers("/api/appointments/pay/**").permitAll()
                        .requestMatchers("/api/webhooks/openpay").permitAll()
                        .requestMatchers("/api/appointments/by-token/**").permitAll()
                        .requestMatchers("/api/appointments/**").authenticated()
                        // El doctor usa los módulos de Pacientes, Cobrar y Corte de caja; los servicios
                        // lo acotan a lo suyo (ver DoctorScope). El resto de recepción no es para él.
                        .requestMatchers(HttpMethod.GET, "/api/reception/appointments").hasAnyRole("RECEPTIONIST", "ADMIN", "DOCTOR")
                        .requestMatchers(
                                "/api/reception/patients", "/api/reception/patients/**",
                                "/api/reception/prescriptions/*/pdf",
                                "/api/reception/charge/**",
                                "/api/reception/appointments/*/charge", "/api/reception/appointments/*/payments",
                                "/api/reception/payments/**",
                                "/api/reception/cash-cut", "/api/reception/cash-cut/**"
                        ).hasAnyRole("RECEPTIONIST", "ADMIN", "DOCTOR")
                        .requestMatchers("/api/reception/**").hasAnyRole("RECEPTIONIST", "ADMIN")
                        .requestMatchers("/api/doctor/**").hasAnyRole("DOCTOR", "ADMIN")
                        // Reportes: personal del hospital; qué ve cada quien lo deciden sus permisos.
                        .requestMatchers("/api/reports/**").hasAnyRole("ADMIN", "RECEPTIONIST", "DOCTOR")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/superadmin/**").hasRole("SUPER_ADMIN")
                        .anyRequest().authenticated()
                )
                .userDetailsService(userDetailsService)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(java.util.Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(o -> !o.isEmpty()).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // Lista cerrada en vez de "*": con credenciales activas, solo los headers que la app usa.
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Hospital", "X-Requested-With",
                RequestIdFilter.HEADER));
        cfg.setExposedHeaders(List.of("Content-Disposition", "Retry-After", RequestIdFilter.HEADER));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    /**
     * Jerarquía de roles (patrón 03/14): ADMIN hereda los permisos de método (@PreAuthorize)
     * de los demás roles, sin tener que listar "ADMIN" explícitamente en cada anotación. Los
     * matchers de {@code authorizeHttpRequests} de arriba ya listan ADMIN explícitamente por
     * claridad y no dependen de esta jerarquía (evita acoplar la autorización por URL, ya
     * probada, a la resolución de jerarquía de Spring Security).
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        RoleHierarchyImpl hierarchy = new RoleHierarchyImpl();
        hierarchy.setHierarchy("""
                ROLE_ADMIN > ROLE_RECEPTIONIST
                ROLE_ADMIN > ROLE_DOCTOR
                ROLE_ADMIN > ROLE_PATIENT
                """);
        return hierarchy;
    }
}
