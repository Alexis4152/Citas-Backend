package com.hospital.citas.service;

import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.Prescription;
import com.hospital.citas.entity.User;

import java.time.LocalDate;
import java.time.LocalTime;

public interface EmailService {

    /** Confirmación al paciente, solo si tiene correo capturado. `receiptPdf` es el
     * comprobante de la cita (puede ser null si no se pudo generar) -- se adjunta al correo
     * en vez de solo describirla en el cuerpo. */
    void sendAppointmentConfirmationToPatient(Appointment appointment, byte[] receiptPdf);

    /** Recordatorio automático 2 horas antes de la cita (ver AppointmentServiceImpl
     * #sendTwoHourReminders); no hace nada si el paciente no tiene correo capturado. */
    void sendAppointmentReminder(Appointment appointment);

    /** Enlaces para ver/cancelar/reprogramar las citas de un invitado que perdió su código: se
     * mandan SOLO al correo que dejó al agendar (ver AppointmentServiceImpl
     * #requestGuestAppointmentLinks), nunca se devuelven al navegador. */
    void sendGuestAppointmentLinks(String to, String firstName, java.util.List<Appointment> appointments, String frontendUrl);

    /** Contraseña temporal generada en el alta de Doctor/Recepcionista (patrón 02). Nunca
     * tumba el alta si falla — solo se registra en el log, igual que el resto de los envíos. */
    void sendTemporaryCredentials(User user, String temporaryPassword);

    /** Link de "olvidé mi contraseña" (patrón 02). */
    void sendPasswordResetEmail(User user, String resetUrl);

    void sendNewAppointmentNoticeToDoctor(Appointment appointment, byte[] receiptPdf);

    void sendNewAppointmentNoticeToReceptionists(Appointment appointment, byte[] receiptPdf);

    /** El director del hospital también se entera de cada cita nueva, con su comprobante. */
    void sendNewAppointmentNoticeToAdmins(Appointment appointment, byte[] receiptPdf);

    void sendCancellationNoticeToPatient(Appointment appointment, byte[] receiptPdf);

    void sendCancellationNoticeToDoctor(Appointment appointment, byte[] receiptPdf);

    void sendCancellationNoticeToReceptionists(Appointment appointment, byte[] receiptPdf);

    void sendCancellationNoticeToAdmins(Appointment appointment, byte[] receiptPdf);

    /** Aviso de reprogramación (nueva fecha/hora, con la anterior de contexto) al paciente
     * (solo si tiene correo capturado), con el comprobante actualizado adjunto. */
    void sendRescheduleNoticeToPatient(Appointment appointment, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf);

    void sendRescheduleNoticeToDoctor(Appointment appointment, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf);

    void sendRescheduleNoticeToReceptionists(Appointment appointment, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf);

    void sendRescheduleNoticeToAdmins(Appointment appointment, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf);

    /** El doctor decide caso por caso si se le envía la receta al paciente por correo (nunca
     * automático -- ver PrescriptionServiceImpl.sendByEmail); no hace nada si el paciente no
     * tiene correo capturado. */
    void sendPrescriptionToPatient(Prescription prescription, byte[] prescriptionPdf);
}
