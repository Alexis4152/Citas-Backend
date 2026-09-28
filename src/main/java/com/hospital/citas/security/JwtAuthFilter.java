package com.hospital.citas.security;

import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.tenant.HospitalDirectory;
import com.hospital.citas.tenant.TenantContext;
import com.hospital.citas.tenant.TokenTenantResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Autentica cada request a partir del JWT en {@code Authorization: Bearer <token>} y fija el
 * hospital de la request ({@link TenantContext}):
 * <ul>
 *   <li>Con sesión: el hospital del usuario (el SUPER_ADMIN trabaja en ROOT, sin filtro).
 *       Manda sobre el header: un usuario nunca opera en un hospital que no es el suyo.</li>
 *   <li>Sin sesión: el del header {@code X-Hospital} (slug del link {@code /c/<slug>} que el
 *       frontend manda en cada petición). Un slug inexistente o inactivo responde 404.</li>
 *   <li>Sin ninguno de los dos: NONE, no ve datos de ningún hospital.</li>
 * </ul>
 * Si el token falta o es inválido, deja pasar la request sin autenticar (las reglas de
 * {@code SecurityConfig} la rechazan más adelante si el endpoint lo requiere).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String HOSPITAL_HEADER = "X-Hospital";

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final HospitalDirectory hospitalDirectory;
    private final TokenTenantResolver tokenTenantResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        try {
            User user = authenticate(request);
            if (user != null) {
                TenantContext.set(user.getRole().getName() == RoleName.SUPER_ADMIN || user.getHospitalId() == null
                        ? TenantContext.ROOT : user.getHospitalId());
            } else {
                String slug = request.getHeader(HOSPITAL_HEADER);
                if (slug != null && !slug.isBlank()) {
                    Long hospitalId = hospitalDirectory.activeIdBySlug(slug.trim().toLowerCase());
                    if (hospitalId == null) {
                        writeHospitalNotFound(response);
                        return;
                    }
                    TenantContext.set(hospitalId);
                } else {
                    // Enlaces de cita por token (algunos se mandaron por correo antes de /c/<slug>).
                    Long fromToken = tokenTenantResolver.hospitalIdFromPath(request.getRequestURI());
                    TenantContext.set(fromToken != null ? fromToken : TenantContext.NONE);
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private User authenticate(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null || !jwtTokenProvider.validateToken(token)) {
            return null;
        }
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        if (userId == null) {
            return null;
        }
        User user = userDetailsService.loadActiveUserById(userId);
        // Un hospital desactivado por el SUPER_ADMIN deja fuera también a sus usuarios con sesión abierta.
        if (user == null || (user.getHospitalId() != null && !hospitalDirectory.isActive(user.getHospitalId()))) {
            return null;
        }
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return user;
    }

    private void writeHospitalNotFound(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"success\":false,\"message\":\"Hospital no encontrado o inactivo\"}");
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
