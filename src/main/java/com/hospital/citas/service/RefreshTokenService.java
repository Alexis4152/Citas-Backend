package com.hospital.citas.service;

import com.hospital.citas.entity.User;

public interface RefreshTokenService {

    /** Crea un refresh token nuevo para el usuario y regresa el valor en claro (solo se
     * persiste su hash). */
    String issue(User user);

    /** Valida el token en claro recibido, lo rota (revoca el viejo, emite uno nuevo) y
     * regresa el usuario dueño + el nuevo valor en claro. */
    RotatedToken rotate(String rawToken);

    void revoke(String rawToken);

    void revokeAllForUser(Long userId);

    record RotatedToken(User user, String newRawToken) {
    }
}
