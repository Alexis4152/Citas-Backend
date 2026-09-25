package com.hospital.citas.exception;

/** Cookie de refresh ausente, inválida, expirada o revocada. */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
