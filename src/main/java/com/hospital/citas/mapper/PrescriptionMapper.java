package com.hospital.citas.mapper;

import com.hospital.citas.dto.response.PrescriptionResponse;
import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.Prescription;
import org.springframework.stereotype.Component;

@Component
public class PrescriptionMapper {

    public PrescriptionResponse toResponse(Prescription prescription) {
        Appointment appointment = prescription.getAppointment();
        return PrescriptionResponse.builder()
                .id(prescription.getId())
                .appointmentId(appointment.getId())
                .diagnosis(prescription.getDiagnosis())
                .content(prescription.getContent())
                .doctorName("Dr(a). " + appointment.getDoctor().getUser().getFirstName() + " "
                        + appointment.getDoctor().getUser().getLastName())
                .specialtyName(appointment.getDoctor().getSpecialty().getName())
                .patientName(appointment.getPatient().getFirstName() + " " + appointment.getPatient().getLastName())
                .appointmentDate(appointment.getAppointmentDate())
                .createdAt(prescription.getCreatedAt())
                .voided(prescription.isVoided())
                .voidedAt(prescription.getVoidedAt())
                .voidReason(prescription.getVoidReason())
                .build();
    }
}
