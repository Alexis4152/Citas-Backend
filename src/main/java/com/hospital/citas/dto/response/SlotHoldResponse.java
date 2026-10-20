package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SlotHoldResponse {
    private UUID token;
    private Long doctorId;
    private Long branchId;
    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalDateTime expiresAt;
    /** Segundos que le quedan al apartado: el frontend arma la cuenta regresiva con esto y no
     * con el reloj del dispositivo (que puede estar adelantado o atrasado). */
    private long secondsLeft;
}
