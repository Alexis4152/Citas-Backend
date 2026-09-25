package com.hospital.citas.mapper;

import com.hospital.citas.dto.response.DoctorDetailResponse;
import com.hospital.citas.dto.response.DoctorSummaryResponse;
import com.hospital.citas.entity.Doctor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DoctorMapper {

    private final SpecialtyMapper specialtyMapper;
    private final BranchMapper branchMapper;

    public DoctorSummaryResponse toSummary(Doctor doctor) {
        return DoctorSummaryResponse.builder()
                .id(doctor.getId())
                .firstName(doctor.getUser().getFirstName())
                .lastName(doctor.getUser().getLastName())
                .specialty(specialtyMapper.toResponse(doctor.getSpecialty()))
                .photoUrl(doctor.getPhotoUrl())
                .consultationPrice(doctor.getConsultationPrice())
                .branches(doctor.getBranches().stream().map(branchMapper::toResponse).toList())
                .build();
    }

    public DoctorDetailResponse toDetail(Doctor doctor) {
        return DoctorDetailResponse.builder()
                .id(doctor.getId())
                .firstName(doctor.getUser().getFirstName())
                .lastName(doctor.getUser().getLastName())
                .email(doctor.getUser().getEmail())
                .phone(doctor.getUser().getPhone())
                .specialty(specialtyMapper.toResponse(doctor.getSpecialty()))
                .licenseNumber(doctor.getLicenseNumber())
                .bio(doctor.getBio())
                .photoUrl(doctor.getPhotoUrl())
                .consultationPrice(doctor.getConsultationPrice())
                .prescriptionTemplate(doctor.getPrescriptionTemplate())
                .defaultSlotMinutes(doctor.getDefaultSlotMinutes())
                .branches(doctor.getBranches().stream().map(branchMapper::toResponse).toList())
                .isActive(doctor.getIsActive())
                .build();
    }
}
