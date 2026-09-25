package com.hospital.citas.mapper;

import com.hospital.citas.dto.response.PatientResponse;
import com.hospital.citas.entity.Patient;
import org.springframework.stereotype.Component;

@Component
public class PatientMapper {

    public PatientResponse toResponse(Patient patient) {
        return PatientResponse.builder()
                .id(patient.getId())
                .firstName(patient.getFirstName())
                .lastName(patient.getLastName())
                .phone(patient.getPhone())
                .email(patient.getEmail())
                .hasAccount(patient.getUser() != null)
                .allergies(patient.getAllergies())
                .bloodType(patient.getBloodType() != null ? patient.getBloodType().name() : null)
                .bloodTypeOther(patient.getBloodTypeOther())
                .medicalInfoUpdatedByName(patient.getMedicalInfoUpdatedBy() != null
                        ? patient.getMedicalInfoUpdatedBy().getFirstName() + " " + patient.getMedicalInfoUpdatedBy().getLastName()
                        : null)
                .medicalInfoUpdatedAt(patient.getMedicalInfoUpdatedAt())
                .build();
    }
}
