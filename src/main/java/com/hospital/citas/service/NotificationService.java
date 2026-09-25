package com.hospital.citas.service;

import com.hospital.citas.dto.response.NotificationResponse;
import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.User;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface NotificationService {

    /** Crea una notificación para el doctor de la cita y para cada ADMIN activo. */
    void notifyNewAppointment(Appointment appointment);

    /** Crea una notificación para el doctor de la cita y para cada ADMIN activo. */
    void notifyAppointmentCancelled(Appointment appointment);

    /** Crea una notificación para el doctor de la cita y para cada ADMIN activo, con la
     * fecha/hora anterior para dar contexto del cambio. */
    void notifyAppointmentRescheduled(Appointment appointment, LocalDate oldDate, LocalTime oldStartTime);

    /** Un doctor bloqueó su agenda (vacaciones, junta) en un día que ya tiene citas programadas:
     * avisa al propio doctor, a recepción y a los ADMIN para que las reprogramen o contacten a
     * los pacientes -- el sistema no las mueve ni las cancela solo. */
    void notifyScheduleConflict(com.hospital.citas.entity.Doctor doctor, LocalDate date, long affectedAppointments);

    List<NotificationResponse> listRecent(User currentUser);

    long countUnread(User currentUser);

    void markAsRead(Long notificationId, User currentUser);

    void markAllAsRead(User currentUser);
}
