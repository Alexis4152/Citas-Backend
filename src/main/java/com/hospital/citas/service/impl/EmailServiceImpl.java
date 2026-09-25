package com.hospital.citas.service.impl;

import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.EmailConfig;
import com.hospital.citas.entity.HospitalConfig;
import com.hospital.citas.entity.Prescription;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import com.hospital.citas.repository.UserRepository;
import com.hospital.citas.service.EmailConfigService;
import com.hospital.citas.service.EmailService;
import com.hospital.citas.service.HospitalConfigService;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Envía las notificaciones de citas (confirmación, aviso al doctor/recepción/admin,
 * cancelación) por correo, con el comprobante en PDF adjunto cuando se generó (ver
 * AppointmentServiceImpl -- el adjunto es opcional, nunca bloquea el envío). La
 * configuración SMTP se lee de la tabla {@code email_config} en cada envío (vía
 * {@link EmailConfigService}) en vez de propiedades estáticas de Spring — así el ADMIN puede
 * capturar/cambiar las credenciales desde {@code /api/admin/email-config} sin reiniciar el
 * Backend.
 * <p>
 * Nunca deja que una falla de SMTP tumbe el agendado/cancelación: cada método atrapa sus
 * propias excepciones y solo registra en el log.
 */
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Respaldo cuando la especialidad del doctor no tiene recomendaciones propias
     * capturadas (ver Specialty#recommendations, editable desde /admin/especialidades). */
    private static final String DEFAULT_RECOMMENDATIONS =
            "Llega 15 minutos antes de tu cita, trae una identificación oficial, "
            + "y si no vas a poder asistir cancela con al menos 24 horas de anticipación para "
            + "liberar el espacio a otro paciente.";

    private String recommendationsFor(Appointment appointment) {
        String custom = appointment.getDoctor().getSpecialty().getRecommendations();
        String body = (custom != null && !custom.isBlank()) ? custom.trim() : DEFAULT_RECOMMENDATIONS;
        return "Recomendaciones: " + body;
    }

    private final EmailConfigService emailConfigService;
    private final HospitalConfigService hospitalConfigService;
    private final UserRepository userRepository;

    @Override
    @Async("notificationExecutor")
    public void sendAppointmentConfirmationToPatient(Appointment appointment, byte[] receiptPdf) {
        String to = appointment.getPatient().getEmail();
        if (to == null || to.isBlank()) {
            log.info("Cita {} sin correo de paciente capturado; se omite la confirmación", appointment.getId());
            return;
        }
        EmailConfig mailConfig = emailConfigService.getEntity();
        if (!Boolean.TRUE.equals(mailConfig.getEnabled())) {
            log.info("Envío de correo deshabilitado; se omite confirmación de la cita {}", appointment.getId());
            return;
        }
        try {
            HospitalConfig hospital = hospitalConfigService.getEntity();
            String subject = "Confirmación de tu cita — " + hospital.getName();
            String html = """
                    <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#1f2937;">
                      <h2 style="color:%s;">%s</h2>
                      <p>Hola %s, tu cita fue agendada con éxito:</p>
                      <ul>
                        <li><strong>Doctor:</strong> %s %s (%s)</li>
                        <li><strong>Sede:</strong> %s — %s</li>
                        <li><strong>Fecha:</strong> %s</li>
                        <li><strong>Hora:</strong> %s</li>
                      </ul>
                      <hr style="border:none;border-top:1px solid #e5e7eb;margin:20px 0;">
                      <p style="color:#6b7280;font-size:13px;text-align:justify;">%s</p>
                    </div>
                    """.formatted(
                    color(hospital), hospital.getName(), appointment.getPatient().getFirstName(),
                    appointment.getDoctor().getUser().getFirstName(), appointment.getDoctor().getUser().getLastName(),
                    appointment.getDoctor().getSpecialty().getName(),
                    appointment.getBranch().getName(), nullSafe(appointment.getBranch().getAddress()),
                    appointment.getAppointmentDate().format(DATE_FMT), appointment.getStartTime().format(TIME_FMT),
                    recommendationsFor(appointment));
            send(mailConfig, to, subject, html, appointment.getId(), receiptPdf);
            log.info("Confirmación de la cita {} enviada a {}", appointment.getId(), to);
        } catch (Exception e) {
            log.error("No se pudo enviar la confirmación de la cita {} a {}", appointment.getId(), to, e);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendGuestAppointmentLinks(String to, String firstName, java.util.List<Appointment> appointments, String frontendUrl) {
        EmailConfig mailConfig = emailConfigService.getEntity();
        if (!Boolean.TRUE.equals(mailConfig.getEnabled())) {
            log.info("Envío de correo deshabilitado; se omiten los enlaces de citas de invitado");
            return;
        }
        try {
            HospitalConfig hospital = hospitalConfigService.getEntity();
            StringBuilder items = new StringBuilder();
            for (Appointment a : appointments) {
                items.append("<li style=\"margin-bottom:10px;\"><strong>")
                        .append(a.getAppointmentDate().format(DATE_FMT)).append(" ")
                        .append(a.getStartTime().format(TIME_FMT)).append("</strong> — ")
                        .append(a.getDoctor().getUser().getFirstName()).append(" ")
                        .append(a.getDoctor().getUser().getLastName()).append(" (")
                        .append(a.getDoctor().getSpecialty().getName()).append(")<br>")
                        .append("<a href=\"").append(frontendUrl).append("/cancelar-cita/").append(a.getCancelToken())
                        .append("\" style=\"color:").append(color(hospital)).append(";\">Ver, reprogramar o cancelar esta cita</a></li>");
            }
            String subject = "Tus citas programadas — " + hospital.getName();
            String html = """
                    <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#1f2937;">
                      <h2 style="color:%s;">Tus citas programadas</h2>
                      <p>Hola %s, alguien solicitó recuperar el acceso a tus citas. Estos son los enlaces:</p>
                      <ul style="padding-left:18px;">%s</ul>
                      <p style="color:#6b7280;font-size:13px;text-align:justify;">Si no fuiste tú, ignora este correo: nadie más recibe esta información.</p>
                    </div>
                    """.formatted(color(hospital), firstName, items);
            send(mailConfig, to, subject, html, (String) null, null);
            log.info("Enlaces de citas de invitado enviados a {}", to);
        } catch (Exception ex) {
            log.error("No se pudieron enviar los enlaces de citas de invitado a {}", to, ex);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendAppointmentReminder(Appointment appointment) {
        String to = appointment.getPatient().getEmail();
        if (to == null || to.isBlank()) {
            log.info("Cita {} sin correo de paciente capturado; se omite el recordatorio", appointment.getId());
            return;
        }
        EmailConfig mailConfig = emailConfigService.getEntity();
        if (!Boolean.TRUE.equals(mailConfig.getEnabled())) {
            log.info("Envío de correo deshabilitado; se omite el recordatorio de la cita {}", appointment.getId());
            return;
        }
        try {
            HospitalConfig hospital = hospitalConfigService.getEntity();
            String subject = "Recordatorio: tu cita es en 2 horas — " + hospital.getName();
            String html = """
                    <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#1f2937;">
                      <h2 style="color:%s;">Tu cita es en 2 horas</h2>
                      <p>Hola %s, este es un recordatorio de tu próxima cita:</p>
                      <ul>
                        <li><strong>Doctor:</strong> %s %s (%s)</li>
                        <li><strong>Sede:</strong> %s — %s</li>
                        <li><strong>Fecha:</strong> %s</li>
                        <li><strong>Hora:</strong> %s</li>
                      </ul>
                      <hr style="border:none;border-top:1px solid #e5e7eb;margin:20px 0;">
                      <p style="color:#6b7280;font-size:13px;text-align:justify;">%s</p>
                    </div>
                    """.formatted(
                    color(hospital), appointment.getPatient().getFirstName(),
                    appointment.getDoctor().getUser().getFirstName(), appointment.getDoctor().getUser().getLastName(),
                    appointment.getDoctor().getSpecialty().getName(),
                    appointment.getBranch().getName(), nullSafe(appointment.getBranch().getAddress()),
                    appointment.getAppointmentDate().format(DATE_FMT), appointment.getStartTime().format(TIME_FMT),
                    recommendationsFor(appointment));
            send(mailConfig, to, subject, html, (String) null, null);
            log.info("Recordatorio de la cita {} enviado a {}", appointment.getId(), to);
        } catch (Exception e) {
            log.error("No se pudo enviar el recordatorio de la cita {} a {}", appointment.getId(), to, e);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendTemporaryCredentials(User user, String temporaryPassword) {
        EmailConfig mailConfig = emailConfigService.getEntity();
        if (!Boolean.TRUE.equals(mailConfig.getEnabled())) {
            log.info("Envío de correo deshabilitado; el ADMIN debe comunicar la contraseña temporal de {} manualmente", user.getEmail());
            return;
        }
        try {
            HospitalConfig hospital = hospitalConfigService.getEntity();
            String subject = "Tu cuenta en " + hospital.getName();
            String html = """
                    <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#1f2937;">
                      <h2 style="color:%s;">%s</h2>
                      <p>Hola %s, se creó una cuenta para ti en %s. Estos son tus datos de acceso:</p>
                      <ul>
                        <li><strong>Correo:</strong> %s</li>
                        <li><strong>Contraseña temporal:</strong> %s</li>
                      </ul>
                      <p>Por seguridad, se te pedirá cambiarla la primera vez que inicies sesión.</p>
                    </div>
                    """.formatted(color(hospital), subject, user.getFirstName(), hospital.getName(),
                    user.getEmail(), temporaryPassword);
            send(mailConfig, user.getEmail(), subject, html, (String) null, null);
            log.info("Contraseña temporal enviada a {}", user.getEmail());
        } catch (Exception e) {
            log.error("No se pudo enviar la contraseña temporal a {}", user.getEmail(), e);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendPasswordResetEmail(User user, String resetUrl) {
        EmailConfig mailConfig = emailConfigService.getEntity();
        if (!Boolean.TRUE.equals(mailConfig.getEnabled())) {
            log.info("Envío de correo deshabilitado; se omite el correo de recuperación de {}", user.getEmail());
            return;
        }
        try {
            HospitalConfig hospital = hospitalConfigService.getEntity();
            String subject = "Recupera tu contraseña — " + hospital.getName();
            String html = """
                    <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#1f2937;">
                      <h2 style="color:%s;">%s</h2>
                      <p>Hola %s, solicitaste restablecer tu contraseña. Este enlace es válido por 30 minutos:</p>
                      <p><a href="%s" style="color:%s;">Restablecer mi contraseña</a></p>
                      <p style="color:#6b7280;font-size:13px;">Si no solicitaste este cambio, ignora este correo.</p>
                    </div>
                    """.formatted(color(hospital), subject, user.getFirstName(), resetUrl, color(hospital));
            send(mailConfig, user.getEmail(), subject, html, (String) null, null);
            log.info("Correo de recuperación de contraseña enviado a {}", user.getEmail());
        } catch (Exception e) {
            log.error("No se pudo enviar el correo de recuperación a {}", user.getEmail(), e);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendNewAppointmentNoticeToDoctor(Appointment appointment, byte[] receiptPdf) {
        String to = appointment.getDoctor().getUser().getEmail();
        sendStaffNotice(appointment, to, "Nueva cita agendada",
                "Tienes una nueva cita agendada con " + appointment.getPatient().getFirstName()
                        + " " + appointment.getPatient().getLastName() + ".", receiptPdf);
    }

    @Override
    @Async("notificationExecutor")
    public void sendNewAppointmentNoticeToReceptionists(Appointment appointment, byte[] receiptPdf) {
        for (User receptionist : userRepository.findByRole_NameAndIsActiveTrue(RoleName.RECEPTIONIST)) {
            sendStaffNotice(appointment, receptionist.getEmail(), "Nueva cita agendada",
                    "Se agendó una nueva cita para el Dr(a). " + appointment.getDoctor().getUser().getFirstName()
                            + " " + appointment.getDoctor().getUser().getLastName() + ".", receiptPdf);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendNewAppointmentNoticeToAdmins(Appointment appointment, byte[] receiptPdf) {
        for (User admin : userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN)) {
            sendStaffNotice(appointment, admin.getEmail(), "Nueva cita agendada",
                    "Se agendó una nueva cita para el Dr(a). " + appointment.getDoctor().getUser().getFirstName()
                            + " " + appointment.getDoctor().getUser().getLastName() + ".", receiptPdf);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendCancellationNoticeToPatient(Appointment appointment, byte[] receiptPdf) {
        String to = appointment.getPatient().getEmail();
        if (to == null || to.isBlank()) {
            return;
        }
        sendStaffNotice(appointment, to, "Cita cancelada", "Tu cita fue cancelada.", receiptPdf);
    }

    @Override
    @Async("notificationExecutor")
    public void sendCancellationNoticeToDoctor(Appointment appointment, byte[] receiptPdf) {
        sendStaffNotice(appointment, appointment.getDoctor().getUser().getEmail(), "Cita cancelada",
                "La cita con " + appointment.getPatient().getFirstName() + " " + appointment.getPatient().getLastName()
                        + " fue cancelada.", receiptPdf);
    }

    @Override
    @Async("notificationExecutor")
    public void sendCancellationNoticeToReceptionists(Appointment appointment, byte[] receiptPdf) {
        for (User receptionist : userRepository.findByRole_NameAndIsActiveTrue(RoleName.RECEPTIONIST)) {
            sendStaffNotice(appointment, receptionist.getEmail(), "Cita cancelada",
                    "Se canceló la cita del Dr(a). " + appointment.getDoctor().getUser().getFirstName()
                            + " " + appointment.getDoctor().getUser().getLastName() + ".", receiptPdf);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendCancellationNoticeToAdmins(Appointment appointment, byte[] receiptPdf) {
        for (User admin : userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN)) {
            sendStaffNotice(appointment, admin.getEmail(), "Cita cancelada",
                    "Se canceló la cita del Dr(a). " + appointment.getDoctor().getUser().getFirstName()
                            + " " + appointment.getDoctor().getUser().getLastName() + ".", receiptPdf);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendRescheduleNoticeToPatient(Appointment appointment, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf) {
        String to = appointment.getPatient().getEmail();
        if (to == null || to.isBlank()) {
            return;
        }
        sendRescheduleNotice(appointment, to, "Tu cita fue reprogramada.", oldDate, oldStartTime, receiptPdf);
    }

    @Override
    @Async("notificationExecutor")
    public void sendRescheduleNoticeToDoctor(Appointment appointment, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf) {
        sendRescheduleNotice(appointment, appointment.getDoctor().getUser().getEmail(),
                "La cita con " + appointment.getPatient().getFirstName() + " " + appointment.getPatient().getLastName()
                        + " fue reprogramada.", oldDate, oldStartTime, receiptPdf);
    }

    @Override
    @Async("notificationExecutor")
    public void sendRescheduleNoticeToReceptionists(Appointment appointment, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf) {
        for (User receptionist : userRepository.findByRole_NameAndIsActiveTrue(RoleName.RECEPTIONIST)) {
            sendRescheduleNotice(appointment, receptionist.getEmail(),
                    "Se reprogramó la cita del Dr(a). " + appointment.getDoctor().getUser().getFirstName()
                            + " " + appointment.getDoctor().getUser().getLastName() + ".", oldDate, oldStartTime, receiptPdf);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendRescheduleNoticeToAdmins(Appointment appointment, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf) {
        for (User admin : userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN)) {
            sendRescheduleNotice(appointment, admin.getEmail(),
                    "Se reprogramó la cita del Dr(a). " + appointment.getDoctor().getUser().getFirstName()
                            + " " + appointment.getDoctor().getUser().getLastName() + ".", oldDate, oldStartTime, receiptPdf);
        }
    }

    private void sendRescheduleNotice(Appointment appointment, String to, String introLine,
                                       LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf) {
        if (to == null || to.isBlank()) {
            return;
        }
        EmailConfig mailConfig = emailConfigService.getEntity();
        if (!Boolean.TRUE.equals(mailConfig.getEnabled())) {
            log.info("Envío de correo deshabilitado; se omite aviso de reprogramación de la cita {}", appointment.getId());
            return;
        }
        try {
            HospitalConfig hospital = hospitalConfigService.getEntity();
            String subject = "Cita reprogramada — " + hospital.getName();
            String html = """
                    <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#1f2937;">
                      <h2 style="color:%s;">%s</h2>
                      <p>%s</p>
                      <ul>
                        <li><strong>Paciente:</strong> %s %s</li>
                        <li><strong>Fecha y hora anterior:</strong> %s, %s</li>
                        <li><strong>Fecha y hora nueva:</strong> %s, %s</li>
                        <li><strong>Sede:</strong> %s</li>
                      </ul>
                    </div>
                    """.formatted(
                    color(hospital), subject, introLine,
                    appointment.getPatient().getFirstName(), appointment.getPatient().getLastName(),
                    oldDate.format(DATE_FMT), oldStartTime.format(TIME_FMT),
                    appointment.getAppointmentDate().format(DATE_FMT), appointment.getStartTime().format(TIME_FMT),
                    appointment.getBranch().getName());
            send(mailConfig, to, subject, html, appointment.getId(), receiptPdf);
        } catch (Exception e) {
            log.error("No se pudo enviar aviso de reprogramación de la cita {} a {}", appointment.getId(), to, e);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendPrescriptionToPatient(Prescription prescription, byte[] prescriptionPdf) {
        Appointment appointment = prescription.getAppointment();
        String to = appointment.getPatient().getEmail();
        if (to == null || to.isBlank()) {
            return;
        }
        EmailConfig mailConfig = emailConfigService.getEntity();
        if (!Boolean.TRUE.equals(mailConfig.getEnabled())) {
            log.info("Envío de correo deshabilitado; se omite el envío de la receta {}", prescription.getId());
            return;
        }
        try {
            HospitalConfig hospital = hospitalConfigService.getEntity();
            String subject = "Tu receta médica — " + hospital.getName();
            String html = """
                    <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#1f2937;">
                      <h2 style="color:%s;">%s</h2>
                      <p>Hola %s, adjunto encontrarás la receta médica de tu consulta con el Dr(a). %s %s
                      del %s.</p>
                    </div>
                    """.formatted(
                    color(hospital), subject, appointment.getPatient().getFirstName(),
                    appointment.getDoctor().getUser().getFirstName(), appointment.getDoctor().getUser().getLastName(),
                    appointment.getAppointmentDate().format(DATE_FMT));
            send(mailConfig, to, subject, html, "receta-" + prescription.getId() + ".pdf", prescriptionPdf);
            log.info("Receta {} enviada por correo a {}", prescription.getId(), to);
        } catch (Exception e) {
            log.error("No se pudo enviar la receta {} a {}", prescription.getId(), to, e);
        }
    }

    private void sendStaffNotice(Appointment appointment, String to, String subjectPrefix, String introLine, byte[] receiptPdf) {
        if (to == null || to.isBlank()) {
            return;
        }
        EmailConfig mailConfig = emailConfigService.getEntity();
        if (!Boolean.TRUE.equals(mailConfig.getEnabled())) {
            log.info("Envío de correo deshabilitado; se omite aviso '{}' de la cita {}", subjectPrefix, appointment.getId());
            return;
        }
        try {
            HospitalConfig hospital = hospitalConfigService.getEntity();
            String subject = subjectPrefix + " — " + hospital.getName();
            String html = """
                    <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#1f2937;">
                      <h2 style="color:%s;">%s</h2>
                      <p>%s</p>
                      <ul>
                        <li><strong>Paciente:</strong> %s %s</li>
                        <li><strong>Fecha:</strong> %s</li>
                        <li><strong>Hora:</strong> %s</li>
                        <li><strong>Sede:</strong> %s</li>
                      </ul>
                    </div>
                    """.formatted(
                    color(hospital), subject, introLine,
                    appointment.getPatient().getFirstName(), appointment.getPatient().getLastName(),
                    appointment.getAppointmentDate().format(DATE_FMT), appointment.getStartTime().format(TIME_FMT),
                    appointment.getBranch().getName());
            send(mailConfig, to, subject, html, appointment.getId(), receiptPdf);
        } catch (Exception e) {
            log.error("No se pudo enviar aviso '{}' de la cita {} a {}", subjectPrefix, appointment.getId(), to, e);
        }
    }

    /** `appointmentId`/`receiptPdf` son opcionales (null en los correos que no son de una
     * cita, ej. contraseña temporal): cuando ambos vienen, el comprobante en PDF se adjunta
     * al correo en vez de solo describir la cita en el cuerpo. */
    private void send(EmailConfig mailConfig, String to, String subject, String html,
                       Long appointmentId, byte[] receiptPdf) throws Exception {
        String attachmentFilename = appointmentId != null ? "comprobante-cita-" + appointmentId + ".pdf" : null;
        send(mailConfig, to, subject, html, attachmentFilename, receiptPdf);
    }

    /** Igual que arriba, pero con el nombre del adjunto explícito -- para correos que no
     * adjuntan un comprobante de cita (ej. la receta médica). */
    private void send(EmailConfig mailConfig, String to, String subject, String html,
                       String attachmentFilename, byte[] attachmentPdf) throws Exception {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mailConfig.getSmtpHost());
        sender.setPort(mailConfig.getSmtpPort());
        sender.setUsername(mailConfig.getSmtpUsername());
        sender.setPassword(mailConfig.getSmtpPassword());
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, attachmentPdf != null, "UTF-8");
        helper.setTo(to);
        String from = mailConfig.getFromAddress() != null && !mailConfig.getFromAddress().isBlank()
                ? mailConfig.getFromAddress() : mailConfig.getSmtpUsername();
        helper.setFrom(from);
        helper.setSubject(subject);
        helper.setText(html, true);
        if (attachmentPdf != null) {
            helper.addAttachment(attachmentFilename, new ByteArrayResource(attachmentPdf));
        }
        sender.send(message);
    }

    private String color(HospitalConfig hospital) {
        return hospital.getPrimaryColor() != null ? hospital.getPrimaryColor() : "#0F766E";
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
