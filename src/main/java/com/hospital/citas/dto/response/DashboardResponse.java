package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DashboardResponse {
    private long appointmentsToday;
    private long appointmentsThisWeek;
    private long activeDoctors;
    private long activeBranches;
    private long activeReceptionists;
    private long cancelledThisWeek;
}
