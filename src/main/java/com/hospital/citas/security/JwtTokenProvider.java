package com.hospital.citas.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import com.hospital.citas.entity.User;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * Genera y valida los JWT usados para autenticar la API. Firma HMAC-SHA256, subject = id del
 * usuario (lo que {@link JwtAuthFilter} usa para recuperarlo en cada request). Es el id y no el
 * correo porque el mismo correo puede tener cuenta en varios hospitales.
 */
@Component
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration}")
    private long expiration;

    public String generateToken(User user) {
        return Jwts.builder()
                .setSubject(String.valueOf(user.getId()))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** Id del usuario del token, o null si el token es de un formato anterior (subject = correo). */
    public Long getUserIdFromToken(String token) {
        try {
            return Long.valueOf(parseClaims(token).getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
