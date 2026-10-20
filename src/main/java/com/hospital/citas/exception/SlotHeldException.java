package com.hospital.citas.exception;

import java.time.LocalDateTime;

/** Otra persona tiene ese horario apartado mientras termina de agendar (ver SlotHold). */
public class SlotHeldException extends RuntimeException {

    private final LocalDateTime heldUntil;

    public SlotHeldException(String message, LocalDateTime heldUntil) {
        super(message);
        this.heldUntil = heldUntil;
    }

    public LocalDateTime getHeldUntil() {
        return heldUntil;
    }
}
