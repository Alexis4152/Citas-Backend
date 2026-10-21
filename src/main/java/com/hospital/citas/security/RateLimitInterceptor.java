package com.hospital.citas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Límite de peticiones por IP en los endpoints que se pueden abusar sin sesión (o que mandan
 * correos, apartan horarios o tocan pagos). Complementa al bloqueo por cuenta del login: ese
 * frena a quien prueba muchas contraseñas en UNA cuenta; este, a quien prueba muchas cuentas o
 * enlaces desde la misma IP. Responde 429 con {@code Retry-After} (patrón de nexora-hotel).
 * <p>
 * Las reglas se revisan en orden y aplica la PRIMERA que coincide: las específicas van antes de
 * las generales. Los enlaces con token ({@code /cancel/{token}}, etc.) también se limitan:
 * adivinar un UUID es inviable, pero sin límite alguien podría intentarlo sin freno.
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private record Rule(String id, String method, String pattern, int capacity, Duration window) {
    }

    private static final List<Rule> RULES = List.of(
            // Acceso
            new Rule("login", "POST", "/api/auth/login", 10, Duration.ofMinutes(1)),
            new Rule("register", "POST", "/api/auth/register", 5, Duration.ofMinutes(15)),
            new Rule("forgot", "POST", "/api/auth/forgot-password", 3, Duration.ofMinutes(15)),
            new Rule("reset", "POST", "/api/auth/reset-password", 10, Duration.ofMinutes(15)),
            new Rule("refresh", "POST", "/api/auth/refresh", 60, Duration.ofMinutes(1)),
            new Rule("change-password", "POST", "/api/auth/change-password", 10, Duration.ofMinutes(15)),
            // Agendado público: crear citas manda correos y ocupa horarios.
            new Rule("guest-booking", "POST", "/api/appointments/guest", 5, Duration.ofMinutes(1)),
            new Rule("guest-lookup", "POST", "/api/appointments/guest-lookup", 5, Duration.ofMinutes(15)),
            new Rule("slot-hold", "POST", "/api/public/slot-holds", 30, Duration.ofMinutes(1)),
            new Rule("slot-release", "DELETE", "/api/public/slot-holds/*", 60, Duration.ofMinutes(1)),
            // Enlaces con token de la cita (invitado).
            new Rule("token-cancel", "POST", "/api/appointments/cancel/*", 10, Duration.ofMinutes(15)),
            new Rule("token-reschedule", "POST", "/api/appointments/reschedule/*", 10, Duration.ofMinutes(15)),
            new Rule("token-pay", "POST", "/api/appointments/pay/*", 10, Duration.ofMinutes(15)),
            new Rule("token-view", "GET", "/api/appointments/by-token/*", 30, Duration.ofMinutes(1)),
            new Rule("token-receipt", "GET", "/api/appointments/guest-receipt/*", 20, Duration.ofMinutes(1)),
            // ARCO: avisa por correo al hospital.
            new Rule("arco", "POST", "/api/public/arco-requests", 3, Duration.ofMinutes(15)),
            new Rule("arco-track", "GET", "/api/public/arco/**", 60, Duration.ofMinutes(15)),
            new Rule("arco-track-write", "POST", "/api/public/arco/**", 15, Duration.ofMinutes(15)),
            new Rule("activate", "POST", "/api/auth/activate/**", 10, Duration.ofMinutes(15)),
            // Pagos y webhook (OpenPay reintenta, por eso es amplio).
            new Rule("webhook", "POST", "/api/webhooks/openpay", 600, Duration.ofMinutes(1)),
            // Disponibilidad: el calendario la refresca cada pocos segundos mientras está abierto.
            new Rule("availability-stream", "GET", "/api/public/doctors/*/availability/stream", 30, Duration.ofMinutes(1)),
            new Rule("availability", "GET", "/api/public/doctors/*/availability", 90, Duration.ofMinutes(1)),
            new Rule("public", "GET", "/api/public/**", 300, Duration.ofMinutes(1)));

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        for (Rule rule : RULES) {
            if (rule.method().equals(request.getMethod()) && matcher.match(rule.pattern(), path)) {
                long wait = rateLimiterService.secondsToWait(rule.id() + "|" + request.getRemoteAddr(),
                        rule.capacity(), rule.window());
                if (wait == 0) {
                    return true;
                }
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(wait));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                objectMapper.writeValue(response.getWriter(),
                        ApiResponse.error("Demasiadas solicitudes. Intenta de nuevo en " + humanize(wait) + "."));
                return false;
            }
        }
        return true;
    }

    private static String humanize(long seconds) {
        if (seconds < 60) {
            return seconds + " segundos";
        }
        long minutes = (seconds + 59) / 60;
        return minutes == 1 ? "1 minuto" : minutes + " minutos";
    }
}
