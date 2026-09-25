package com.hospital.citas.mapper;

import com.hospital.citas.dto.response.PaymentResponse;
import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.Payment;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    public PaymentResponse toResponse(Payment p) {
        Appointment a = p.getAppointment();
        return PaymentResponse.builder()
                .id(p.getId())
                .appointmentId(a.getId())
                .amount(p.getAmount())
                .method(p.getMethod().name())
                .status(p.getStatus().name())
                .channel(p.getChannel().name())
                .refundStatus(p.getRefundStatus().name())
                .refundReason(p.getRefundReason())
                .refundedAt(p.getRefundedAt())
                .cashReceived(p.getCashReceived())
                .changeGiven(p.getChangeGiven())
                .reference(p.getReference())
                .authorizationCode(p.getAuthorizationCode())
                .speiClabe(p.getSpeiClabe())
                .speiBank(p.getSpeiBank())
                .failureReason(p.getFailureReason())
                .createdAt(p.getCreatedAt())
                .receivedByName(p.getReceivedBy() == null ? null
                        : p.getReceivedBy().getFirstName() + " " + p.getReceivedBy().getLastName())
                .patientName(a.getPatient().getFirstName() + " " + a.getPatient().getLastName())
                .doctorName("Dr(a). " + a.getDoctor().getUser().getFirstName() + " " + a.getDoctor().getUser().getLastName())
                .specialtyName(a.getDoctor().getSpecialty().getName())
                .appointmentDate(a.getAppointmentDate())
                .appointmentTime(a.getStartTime())
                .build();
    }
}
