package com.hospital.citas.util;

import java.security.SecureRandom;

/**
 * Genera la contraseña temporal para altas de Doctor/Recepcionista hechas por un ADMIN
 * (patrón 02): se excluyen caracteres ambiguos (0/O, 1/l/I) para que se pueda transcribir a
 * mano sin errores si se comunica por teléfono.
 */
public final class TempPasswordGenerator {

    private static final String CHARS = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%";
    private static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TempPasswordGenerator() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
