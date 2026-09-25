package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DoctorScheduleResponse {
    private Long id;
    private Long branchId;
    private String branchName;
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer slotMinutes;
}
