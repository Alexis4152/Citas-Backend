package com.hospital.citas.service;

import com.hospital.citas.dto.request.DoctorScheduleExceptionRequest;
import com.hospital.citas.dto.request.DoctorScheduleRequest;
import com.hospital.citas.dto.response.DoctorScheduleExceptionResponse;
import com.hospital.citas.dto.response.DoctorScheduleResponse;

import java.util.List;

public interface DoctorScheduleService {

    List<DoctorScheduleResponse> list(Long doctorId);

    DoctorScheduleResponse create(Long doctorId, DoctorScheduleRequest request);

    DoctorScheduleResponse update(Long doctorId, Long scheduleId, DoctorScheduleRequest request);

    void delete(Long doctorId, Long scheduleId);

    List<DoctorScheduleExceptionResponse> listExceptions(Long doctorId);

    DoctorScheduleExceptionResponse createException(Long doctorId, DoctorScheduleExceptionRequest request);

    void deleteException(Long doctorId, Long exceptionId);
}
