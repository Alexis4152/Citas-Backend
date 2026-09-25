package com.hospital.citas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;

/**
 * Límite de peticiones por IP en endpoints públicos sin autenticación (hallazgo "Alto" de la
 * auditoría): sin esto, alguien puede saturar el agendado de invitado, la búsqueda pública de
 * citas o "olvidé mi contraseña" en un bucle sin límite. También cubre /auth/login como
 * segunda capa además del bloqueo por cuenta de {@link com.hospital.citas.service.impl
 * .AuthServiceImpl#login} -- ese bloquea una cuenta puntual, este frena a alguien probando
 * MUCHAS cuentas distintas desde la misma IP.
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private record Rule(int capacity, Duration window) {}

    private static final Map<String, Rule> RULES = Map.of(
            "POST:/api/appointments/guest", new Rule(5, Duration.ofMinutes(1)),
            // Manda un correo (a la dirección registrada de la cita): límite bajo para que nadie lo use
            // para inundar de correos a un tercero.
            "POST:/api/appointments/guest-lookup", new Rule(5, Duration.ofMinutes(15)),
            "POST:/api/auth/forgot-password", new Rule(3, Duration.ofMinutes(15)),
            "POST:/api/auth/login", new Rule(10, Duration.ofMinutes(1))
    );

    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Rule rule = RULES.get(request.getMethod() + ":" + request.getRequestURI());
        if (rule == null) {
            return true;
        }
        String key = request.getRequestURI() + "|" + request.getRemoteAddr();
        if (rateLimiterService.tryConsume(key, rule.capacity(), rule.window())) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error("Demasiadas solicitudes. Intenta de nuevo más tarde.")));
        return false;
    }
}
