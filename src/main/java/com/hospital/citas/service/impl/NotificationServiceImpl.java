package com.hospital.citas.service.impl;

import com.hospital.citas.dto.response.NotificationResponse;
import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.Notification;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.NotificationType;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.NotificationRepository;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Notificación en app (independiente del correo), consultada por polling (patrón 14) en vez
 * de necesitar websockets -- ver NotificationController y el hook usePolling del frontend.
 * <p>
 * Alcance por rol: doctor/recepción/admin ven las 3 (nueva cita, cancelación,
 * reprogramación) porque operan sobre las citas de todos; el paciente solo ve cancelación y
 * reprogramación (el agendado ya lo ve de inmediato en pantalla al hacerlo él mismo) y solo
 * si tiene cuenta -- un paciente invitado (sin User, ver Appointment.patient.user nulo) no
 * tiene dónde ver una campanita, así que solo se entera por correo.
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final AppointmentRepository appointmentRepository;

    @Override
    @Transactional
    public void notifyNewAppointment(Appointment appointment) {
        String patientName = appointment.getPatient().getFirstName() + " " + appointment.getPatient().getLastName();
        String message = "Nueva cita con " + patientName + " el " + formatWhen(appointment);
        notifyStaff(appointment, NotificationType.NEW_APPOINTMENT, message);
    }

    @Override
    @Transactional
    public void notifyAppointmentCancelled(Appointment appointment) {
        String patientName = appointment.getPatient().getFirstName() + " " + appointment.getPatient().getLastName();
        String staffMessage = "Cita cancelada: " + patientName + " el " + formatWhen(appointment);
        notifyStaff(appointment, NotificationType.APPOINTMENT_CANCELLED, staffMessage);

        String patientMessage = "Tu cita con Dr(a). " + doctorLastName(appointment) + " el " + formatWhen(appointment) + " fue cancelada";
        notifyPatientIfRegistered(appointment, NotificationType.APPOINTMENT_CANCELLED, patientMessage);
    }

    @Override
    @Transactional
    public void notifyAppointmentRescheduled(Appointment appointment, LocalDate oldDate, LocalTime oldStartTime) {
        String patientName = appointment.getPatient().getFirstName() + " " + appointment.getPatient().getLastName();
        String oldWhen = oldDate.format(DATE_FMT) + " " + oldStartTime.format(TIME_FMT);
        String staffMessage = "Cita reprogramada: " + patientName + " ahora el " + formatWhen(appointment) + " (antes " + oldWhen + ")";
        notifyStaff(appointment, NotificationType.APPOINTMENT_RESCHEDULED, staffMessage);

        String patientMessage = "Tu cita con Dr(a). " + doctorLastName(appointment) + " fue reprogramada: ahora el "
                + formatWhen(appointment) + " (antes " + oldWhen + ")";
        notifyPatientIfRegistered(appointment, NotificationType.APPOINTMENT_RESCHEDULED, patientMessage);
    }

    @Override
    @Transactional
    public void notifyScheduleConflict(com.hospital.citas.entity.Doctor doctor, LocalDate date, long affectedAppointments) {
        String message = "Dr(a). " + doctor.getUser().getFirstName() + " " + doctor.getUser().getLastName()
                + " bloqueó su agenda el " + date.format(DATE_FMT) + " y tiene " + affectedAppointments
                + " cita(s) programada(s) afectadas: hay que reprogramarlas o avisar a los pacientes";
        create(doctor.getUser(), NotificationType.SCHEDULE_CONFLICT, message, null);
        for (User receptionist : userRepository.findByRole_NameAndIsActiveTrue(RoleName.RECEPTIONIST)) {
            create(receptionist, NotificationType.SCHEDULE_CONFLICT, message, null);
        }
        for (User admin : userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN)) {
            create(admin, NotificationType.SCHEDULE_CONFLICT, message, null);
        }
    }

    /** Doctor de la cita + todos los RECEPTIONIST y ADMIN activos -- los 3 roles que operan
     * sobre las citas de cualquier paciente, no solo las propias. */
    private void notifyStaff(Appointment appointment, NotificationType type, String message) {
        create(appointment.getDoctor().getUser(), type, message, appointment.getId());
        for (User receptionist : userRepository.findByRole_NameAndIsActiveTrue(RoleName.RECEPTIONIST)) {
            create(receptionist, type, message, appointment.getId());
        }
        for (User admin : userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN)) {
            create(admin, type, message, appointment.getId());
        }
    }

    private void notifyPatientIfRegistered(Appointment appointment, NotificationType type, String message) {
        User patientUser = appointment.getPatient().getUser();
        if (patientUser != null) {
            create(patientUser, type, message, appointment.getId());
        }
    }

    private void create(User recipient, NotificationType type, String message, Long appointmentId) {
        notificationRepository.save(Notification.builder()
                .recipient(recipient)
                .type(type)
                .message(message)
                .appointmentId(appointmentId)
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> listRecent(User currentUser) {
        List<Notification> notifications = notificationRepository.findTop20ByRecipient_IdOrderByCreatedAtDesc(currentUser.getId());

        // Fecha ACTUAL de cada cita referenciada, en un solo query en lote (no N+1) -- si se
        // volvió a reprogramar después de esta notificación, esto ya refleja dónde vive hoy.
        List<Long> appointmentIds = notifications.stream().map(Notification::getAppointmentId).filter(id -> id != null).toList();
        Map<Long, LocalDate> datesByAppointmentId = appointmentRepository.findAllById(appointmentIds).stream()
                .collect(Collectors.toMap(Appointment::getId, Appointment::getAppointmentDate));

        return notifications.stream()
                .map(n -> toResponse(n, datesByAppointmentId.get(n.getAppointmentId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(User currentUser) {
        return notificationRepository.countByRecipient_IdAndIsReadFalse(currentUser.getId());
    }

    @Override
    @Transactional
    public void markAsRead(Long notificationId, User currentUser) {
        Notification notification = notificationRepository.findByIdAndRecipient_Id(notificationId, currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notificación no encontrada: " + notificationId));
        notification.setIsRead(true);
        notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public void markAllAsRead(User currentUser) {
        notificationRepository.markAllAsRead(currentUser.getId());
    }

    private String formatWhen(Appointment appointment) {
        return appointment.getAppointmentDate().format(DATE_FMT) + " " + appointment.getStartTime().format(TIME_FMT);
    }

    private String doctorLastName(Appointment appointment) {
        return appointment.getDoctor().getUser().getFirstName() + " " + appointment.getDoctor().getUser().getLastName();
    }

    private NotificationResponse toResponse(Notification notification, LocalDate appointmentDate) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .type(notification.getType().name())
                .message(notification.getMessage())
                .appointmentId(notification.getAppointmentId())
                .appointmentDate(appointmentDate)
                .read(notification.getIsRead())
                .createdAt(notification.getCreatedAt())
                .build();
    }
}
