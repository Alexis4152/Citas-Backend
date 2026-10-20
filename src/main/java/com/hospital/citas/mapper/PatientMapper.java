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
                .birthDate(patient.getBirthDate())
                .age(patient.getBirthDate() != null ? java.time.Period.between(patient.getBirthDate(), java.time.LocalDate.now()).getYears() : null)
                .sex(patient.getSex())
                .curp(patient.getCurp())
                .addressLine(patient.getAddressLine())
                .neighborhood(patient.getNeighborhood())
                .city(patient.getCity())
                .state(patient.getState())
                .postalCode(patient.getPostalCode())
                .emergencyContactName(patient.getEmergencyContactName())
                .emergencyContactPhone(patient.getEmergencyContactPhone())
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
