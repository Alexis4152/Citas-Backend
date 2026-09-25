package com.hospital.citas.mapper;

import com.hospital.citas.dto.response.DoctorScheduleExceptionResponse;
import com.hospital.citas.dto.response.DoctorScheduleResponse;
import com.hospital.citas.entity.DoctorSchedule;
import com.hospital.citas.entity.DoctorScheduleException;
import org.springframework.stereotype.Component;

@Component
public class DoctorScheduleMapper {

    public DoctorScheduleResponse toResponse(DoctorSchedule schedule) {
        return DoctorScheduleResponse.builder()
                .id(schedule.getId())
                .branchId(schedule.getBranch().getId())
                .branchName(schedule.getBranch().getName())
                .dayOfWeek(schedule.getDayOfWeek())
                .startTime(schedule.getStartTime())
                .endTime(schedule.getEndTime())
                .slotMinutes(schedule.getSlotMinutes())
                .build();
    }

    public DoctorScheduleExceptionResponse toResponse(DoctorScheduleException exception) {
        return DoctorScheduleExceptionResponse.builder()
                .id(exception.getId())
                .date(exception.getDate())
                .allDay(Boolean.TRUE.equals(exception.getAllDay()))
                .startTime(exception.getStartTime())
                .endTime(exception.getEndTime())
                .reason(exception.getReason())
                .build();
    }
}
