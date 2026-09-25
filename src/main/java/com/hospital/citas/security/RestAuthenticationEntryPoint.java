package com.hospital.citas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Sin esto, un request sin sesión a un endpoint protegido recibe el 401/403 "crudo" default de
 * Spring Security (sin el contrato {@link ApiResponse} del resto de la API), porque el filtro
 * de seguridad actúa antes de que el request llegue al {@code GlobalExceptionHandler}.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error("No autenticado")));
    }
}
