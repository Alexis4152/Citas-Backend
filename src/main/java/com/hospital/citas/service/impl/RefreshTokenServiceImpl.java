package com.hospital.citas.service.impl;

import com.hospital.citas.entity.RefreshToken;
import com.hospital.citas.entity.User;
import com.hospital.citas.exception.InvalidRefreshTokenException;
import com.hospital.citas.repository.RefreshTokenRepository;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Emite y rota los refresh tokens opacos (patrón 01): el valor en claro solo existe en la
 * cookie httpOnly del cliente; en BD únicamente se guarda su hash SHA-256, así que un dump de
 * la base no permite reconstruir sesiones.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.refresh.expiration}")
    private long expirationMs;

    @Override
    @Transactional
    public String issue(User user) {
        String raw = generateRaw();
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hash(raw))
                .expiresAt(LocalDateTime.now().plusNanos(expirationMs * 1_000_000))
                .build();
        refreshTokenRepository.save(token);
        return raw;
    }

    @Override
    @Transactional
    public RotatedToken rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException("Sesión expirada, inicia sesión de nuevo");
        }
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Sesión expirada, inicia sesión de nuevo"));
        if (!existing.isValid()) {
            throw new InvalidRefreshTokenException("Sesión expirada, inicia sesión de nuevo");
        }
        existing.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(existing);

        User user = userRepository.findById(existing.getUser().getId())
                .orElseThrow(() -> new InvalidRefreshTokenException("Sesión expirada, inicia sesión de nuevo"));
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new InvalidRefreshTokenException("Cuenta deshabilitada");
        }
        return new RotatedToken(user, issue(user));
    }

    @Override
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(t -> {
                    t.setRevokedAt(LocalDateTime.now());
                    refreshTokenRepository.save(t);
                });
    }

    @Override
    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllForUser(userId, LocalDateTime.now());
    }

    private String generateRaw() {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
