package com.hospital.citas.mapper;

import com.hospital.citas.dto.response.AppointmentResponse;
import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.AppointmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AppointmentMapper {

    private final DoctorMapper doctorMapper;
    private final BranchMapper branchMapper;
    private final PatientMapper patientMapper;

    public AppointmentResponse toResponse(Appointment appointment) {
        return toResponse(appointment, false);
    }

    public AppointmentResponse toResponse(Appointment appointment, boolean includeCancelToken) {
        boolean canRelease = appointment.getStatus() == AppointmentStatus.CANCELLED
                && Boolean.FALSE.equals(appointment.getSlotReleased())
                && Boolean.TRUE.equals(appointment.getReleaseEligible());

        return AppointmentResponse.builder()
                .id(appointment.getId())
                .doctor(doctorMapper.toSummary(appointment.getDoctor()))
                .branch(branchMapper.toResponse(appointment.getBranch()))
                .patient(patientMapper.toResponse(appointment.getPatient()))
                .appointmentDate(appointment.getAppointmentDate())
                .startTime(appointment.getStartTime())
                .endTime(appointment.getEndTime())
                .status(appointment.getStatus().name())
                .reasonForVisit(appointment.getReasonForVisit())
                .cancelReason(appointment.getCancelReason())
                .cancelledAt(appointment.getCancelledAt())
                .cancelledByName(cancelledByName(appointment))
                .cancelledByRole(cancelledByRole(appointment))
                .slotReleased(appointment.getSlotReleased())
                .releaseEligible(appointment.getReleaseEligible())
                .canRelease(canRelease)
                .releasedAt(appointment.getReleasedAt())
                .releasedByName(releasedByName(appointment))
                .releasedByRole(releasedByRole(appointment))
                .price(appointment.getPrice())
                .paymentStatus(appointment.getPaymentStatus().name())
                .arrivedAt(appointment.getArrivedAt())
                .overbooked(appointment.getOverbooked())
                .cancelToken(includeCancelToken ? appointment.getCancelToken().toString() : null)
                .build();
    }

    private String cancelledByName(Appointment appointment) {
        if (appointment.getStatus() != AppointmentStatus.CANCELLED) {
            return null;
        }
        User user = appointment.getCancelledBy();
        // cancelledBy nulo con status CANCELLED significa que se canceló vía el link
        // público sin cuenta (ver AppointmentServiceImpl.cancelByToken).
        return user != null ? user.getFirstName() + " " + user.getLastName() : "Paciente (invitado)";
    }

    private String cancelledByRole(Appointment appointment) {
        if (appointment.getStatus() != AppointmentStatus.CANCELLED) {
            return null;
        }
        User user = appointment.getCancelledBy();
        return user != null ? user.getRole().getName().name() : "GUEST";
    }

    private String releasedByName(Appointment appointment) {
        User user = appointment.getReleasedBy();
        return user != null ? user.getFirstName() + " " + user.getLastName() : null;
    }

    private String releasedByRole(Appointment appointment) {
        User user = appointment.getReleasedBy();
        return user != null ? user.getRole().getName().name() : null;
    }
}
