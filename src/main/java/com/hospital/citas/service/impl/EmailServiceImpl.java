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
import com.hospital.citas.service.mail.EmailTemplate;
import com.hospital.citas.service.mail.EmailTemplate.Tone;
import com.hospital.citas.tenant.TenantLinks;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static com.hospital.citas.service.mail.EmailTemplate.esc;

/**
 * Correos de la plataforma (confirmación, recordatorio, cancelación, reprogramación, avisos al
 * personal, accesos y recetas), con el diseño de {@link EmailTemplate}. La configuración SMTP se
 * lee de {@code email_config} del hospital en cada envío, así el ADMIN la cambia sin reiniciar.
 * <p>
 * Nunca deja que una falla de SMTP tumbe el agendado o la cancelación: cada método atrapa sus
 * propias excepciones y solo registra en el log. Cada correo va en HTML y en texto plano.
 */
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);
    private static final Locale MX = Locale.forLanguageTag("es-MX");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", MX);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Respaldo cuando la especialidad no tiene recomendaciones propias (ver Specialty#recommendations). */
    private static final String DEFAULT_RECOMMENDATIONS = "Llega 15 minutos antes de tu cita • Trae una identificación oficial "
            + "• Si no vas a poder asistir, cancela con al menos 24 horas de anticipación para liberar el espacio";

    private final EmailConfigService emailConfigService;
    private final HospitalConfigService hospitalConfigService;
    private final TenantLinks tenantLinks;
    private final UserRepository userRepository;

    @Value("${app.public-url:}")
    private String publicUrl;

    private enum Audience { DOCTOR, RECEPTION, ADMIN }

    // ── Paciente ────────────────────────────────────────────────

    @Override
    @Async("notificationExecutor")
    public void sendAppointmentConfirmationToPatient(Appointment a, byte[] receiptPdf) {
        String to = a.getPatient().getEmail();
        if (blank(to)) {
            log.info("Cita {} sin correo de paciente; se omite la confirmación", a.getId());
            return;
        }
        deliver(to, "Tu cita está confirmada", a.getId(), receiptPdf, h -> new EmailTemplate(h.getPrimaryColor())
                .preheader("Dr(a). " + a.getDoctor().getUser().getLastName() + " · " + a.getAppointmentDate().format(LONG_DATE)
                        + " a las " + a.getStartTime().format(TIME_FMT))
                .hero("&#10003;", Tone.SUCCESS, "Cita confirmada", "¡Listo, " + a.getPatient().getFirstName() + "! Tu cita quedó agendada",
                        "Te esperamos en la fecha y hora de abajo. Adjuntamos tu comprobante con el código QR para recepción.")
                .appointmentCard(a, false)
                .rows(appointmentRows(a))
                .button("Ver, reprogramar o cancelar", manageUrl(a))
                .links(List.of(new String[]{"Agregar a mi calendario", EmailTemplate.googleCalendarUrl(a, h.getName())},
                        new String[]{"Cómo llegar", EmailTemplate.mapsUrl(a)}))
                .checklist("Antes de tu cita", recommendations(a))
                .build(h.getName(), logoUrl(h), contact(h), "Recibes este correo porque agendaste una cita en " + h.getName() + "."));
    }

    @Override
    @Async("notificationExecutor")
    public void sendAppointmentReminder(Appointment a) {
        String to = a.getPatient().getEmail();
        if (blank(to)) return;
        deliver(to, "Recordatorio: tu cita es en 2 horas", null, null, h -> new EmailTemplate(h.getPrimaryColor())
                .preheader("Hoy a las " + a.getStartTime().format(TIME_FMT) + " con Dr(a). " + a.getDoctor().getUser().getLastName())
                .hero("&#9200;", Tone.WARNING, "Recordatorio", "Tu cita es en 2 horas",
                        "Hola " + esc(a.getPatient().getFirstName()) + ", te esperamos hoy. Si ya no puedes asistir, avísanos para liberar el espacio.")
                .appointmentCard(a, false)
                .button("Cómo llegar", EmailTemplate.mapsUrl(a))
                .links(List.<String[]>of(new String[]{"Ya no puedo ir: cancelar o reprogramar", manageUrl(a)}))
                .checklist("No olvides", recommendations(a))
                .build(h.getName(), logoUrl(h), contact(h), "Recibes este recordatorio porque tienes una cita programada hoy."));
    }

    @Override
    @Async("notificationExecutor")
    public void sendCancellationNoticeToPatient(Appointment a, byte[] receiptPdf) {
        String to = a.getPatient().getEmail();
        if (blank(to)) return;
        deliver(to, "Tu cita fue cancelada", a.getId(), receiptPdf, h -> {
            EmailTemplate t = new EmailTemplate(h.getPrimaryColor())
                    .preheader("Cita del " + a.getAppointmentDate().format(DATE_FMT) + " cancelada")
                    .hero("&#10005;", Tone.DANGER, "Cita cancelada", "Tu cita fue cancelada",
                            "Hola " + esc(a.getPatient().getFirstName()) + ", esta cita ya no está en la agenda del doctor y el horario quedó libre.")
                    .appointmentCard(a, true);
            if (!blank(a.getCancelReason())) {
                t.notice(Tone.NEUTRAL, "Motivo", esc(a.getCancelReason()));
            }
            String payment = a.getPaymentStatus() != null ? a.getPaymentStatus().name() : "UNPAID";
            if ("REFUNDED".equals(payment)) {
                t.notice(Tone.SUCCESS, "Reembolso", "Te reembolsamos el pago anticipado. Según tu banco, puede tardar algunos días en reflejarse.");
            } else if ("PAID".equals(payment) || "PENDING".equals(payment)) {
                t.notice(Tone.WARNING, "Tu pago anticipado", "El hospital revisará el reembolso y te avisará. Si tienes dudas, contáctanos.");
            }
            return t.button("Agendar una nueva cita", tenantLinks.frontendBase() + "/agendar")
                    .build(h.getName(), logoUrl(h), contact(h), "Recibes este correo porque tenías una cita en " + h.getName() + ".");
        });
    }

    @Override
    @Async("notificationExecutor")
    public void sendRescheduleNoticeToPatient(Appointment a, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf) {
        String to = a.getPatient().getEmail();
        if (blank(to)) return;
        deliver(to, "Tu cita cambió de horario", a.getId(), receiptPdf, h -> new EmailTemplate(h.getPrimaryColor())
                .preheader("Nueva fecha: " + a.getAppointmentDate().format(LONG_DATE) + " a las " + a.getStartTime().format(TIME_FMT))
                .hero("&#8635;", Tone.INFO, "Cita reprogramada", "Tu cita tiene nuevo horario",
                        "Hola " + esc(a.getPatient().getFirstName()) + ", estos son los datos actualizados. Adjuntamos tu nuevo comprobante.")
                .change(oldDate, oldStartTime, a)
                .appointmentCard(a, false)
                .button("Ver mi cita", manageUrl(a))
                .links(List.<String[]>of(new String[]{"Agregar a mi calendario", EmailTemplate.googleCalendarUrl(a, h.getName())}))
                .build(h.getName(), logoUrl(h), contact(h), "Recibes este correo porque tienes una cita en " + h.getName() + "."));
    }

    @Override
    @Async("notificationExecutor")
    public void sendGuestAppointmentLinks(String to, String firstName, List<Appointment> appointments, String frontendUrl) {
        deliver(to, "Tus citas programadas", null, null, h -> {
            EmailTemplate t = new EmailTemplate(h.getPrimaryColor())
                    .preheader("Enlaces para ver, reprogramar o cancelar tus citas")
                    .hero("&#128279;", Tone.BRAND, "Acceso a tus citas", "Aquí están tus citas",
                            "Hola " + esc(firstName) + ", alguien pidió recuperar el acceso a tus citas. Usa el botón de cada una para verla o cambiarla.");
            for (Appointment a : appointments) {
                t.appointmentCard(a, false).button("Abrir esta cita", frontendUrl + "/cancelar-cita/" + a.getCancelToken());
            }
            return t.notice(Tone.NEUTRAL, null, "Si no fuiste tú, ignora este correo: solo tú recibes estos enlaces.")
                    .build(h.getName(), logoUrl(h), contact(h), "Recibes este correo porque se solicitó el acceso a tus citas.");
        });
    }

    @Override
    @Async("notificationExecutor")
    public void sendPrescriptionToPatient(Prescription prescription, byte[] prescriptionPdf) {
        Appointment a = prescription.getAppointment();
        String to = a.getPatient().getEmail();
        if (blank(to)) return;
        deliverWithAttachment(to, "Tu receta médica", "receta-RX-" + String.format("%06d", prescription.getId()) + ".pdf", prescriptionPdf,
                h -> new EmailTemplate(h.getPrimaryColor())
                        .preheader("Receta de tu consulta del " + a.getAppointmentDate().format(DATE_FMT))
                        .hero("Rx", Tone.BRAND, "Receta médica", "Tu receta está lista",
                                "Hola " + esc(a.getPatient().getFirstName()) + ", adjuntamos en PDF la receta de tu consulta. Imprímela para surtirla.")
                        .rows(List.of(
                                new String[]{"Doctor", "Dr(a). " + esc(a.getDoctor().getUser().getFirstName() + " " + a.getDoctor().getUser().getLastName())},
                                new String[]{"Especialidad", esc(a.getDoctor().getSpecialty().getName())},
                                new String[]{"Consulta", esc(a.getAppointmentDate().format(DATE_FMT))},
                                new String[]{"Folio", "RX-" + String.format("%06d", prescription.getId())}))
                        .notice(Tone.WARNING, "Importante", "Sigue las indicaciones de tu doctor y no te automediques. "
                                + "La receta solo es válida con la firma del médico.")
                        .build(h.getName(), logoUrl(h), contact(h), "Recibes este correo porque te atendiste en " + h.getName() + "."));
    }

    // ── Accesos ─────────────────────────────────────────────────

    @Override
    @Async("notificationExecutor")
    public void sendTemporaryCredentials(User user, String temporaryPassword) {
        String loginUrl = tenantLinks.frontendBase() + "/login";
        deliver(user.getEmail(), "Tu cuenta está lista", null, null, h -> new EmailTemplate(h.getPrimaryColor())
                .preheader("Tus datos de acceso a " + h.getName())
                .hero("&#128273;", Tone.BRAND, "Bienvenida", "Hola " + user.getFirstName() + ", ya tienes acceso",
                        "Se creó una cuenta para ti en <strong>" + esc(h.getName()) + "</strong>. Entra con estos datos; "
                                + "te pediremos cambiar la contraseña la primera vez.")
                .rows(List.<String[]>of(new String[]{"Correo", esc(user.getEmail())}))
                .code("Contraseña temporal", temporaryPassword)
                .button("Iniciar sesión", loginUrl)
                .notice(Tone.NEUTRAL, null, "Guarda bien tu contraseña nueva. Nadie del hospital te la pedirá por teléfono ni por correo.")
                .build(h.getName(), logoUrl(h), contact(h), "Recibes este correo porque un administrador creó tu cuenta."));
    }

    @Override
    @Async("notificationExecutor")
    public void sendPasswordResetEmail(User user, String resetUrl) {
        deliver(user.getEmail(), "Restablece tu contraseña", null, null, h -> new EmailTemplate(h.getPrimaryColor())
                .preheader("El enlace vence en 30 minutos")
                .hero("&#128274;", Tone.BRAND, "Seguridad", "Restablece tu contraseña",
                        "Hola " + esc(user.getFirstName()) + ", recibimos una solicitud para cambiar tu contraseña. El enlace vence en 30 minutos.")
                .button("Crear nueva contraseña", resetUrl)
                .notice(Tone.NEUTRAL, null, "Si no lo pediste tú, ignora este correo: tu contraseña actual sigue funcionando.")
                .build(h.getName(), logoUrl(h), contact(h), "Recibes este correo porque se solicitó restablecer tu contraseña."));
    }

    // ── Personal ────────────────────────────────────────────────

    @Override
    @Async("notificationExecutor")
    public void sendNewAppointmentNoticeToDoctor(Appointment a, byte[] receiptPdf) {
        staffNotice(a, a.getDoctor().getUser().getEmail(), Audience.DOCTOR, "Nueva cita agendada", "&#43;", Tone.SUCCESS,
                "Tienes una nueva cita con <strong>" + esc(patientName(a)) + "</strong>.", receiptPdf, null, null);
    }

    @Override
    @Async("notificationExecutor")
    public void sendNewAppointmentNoticeToReceptionists(Appointment a, byte[] receiptPdf) {
        for (User u : userRepository.findByRole_NameAndIsActiveTrue(RoleName.RECEPTIONIST)) {
            staffNotice(a, u.getEmail(), Audience.RECEPTION, "Nueva cita agendada", "&#43;", Tone.SUCCESS,
                    "Se agendó una cita para el Dr(a). " + esc(doctorName(a)) + ".", receiptPdf, null, null);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendNewAppointmentNoticeToAdmins(Appointment a, byte[] receiptPdf) {
        for (User u : userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN)) {
            staffNotice(a, u.getEmail(), Audience.ADMIN, "Nueva cita agendada", "&#43;", Tone.SUCCESS,
                    "Se agendó una cita para el Dr(a). " + esc(doctorName(a)) + ".", receiptPdf, null, null);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendCancellationNoticeToDoctor(Appointment a, byte[] receiptPdf) {
        staffNotice(a, a.getDoctor().getUser().getEmail(), Audience.DOCTOR, "Cita cancelada", "&#10005;", Tone.DANGER,
                "Se canceló tu cita con <strong>" + esc(patientName(a)) + "</strong>. El horario quedó libre.", receiptPdf, null, null);
    }

    @Override
    @Async("notificationExecutor")
    public void sendCancellationNoticeToReceptionists(Appointment a, byte[] receiptPdf) {
        for (User u : userRepository.findByRole_NameAndIsActiveTrue(RoleName.RECEPTIONIST)) {
            staffNotice(a, u.getEmail(), Audience.RECEPTION, "Cita cancelada", "&#10005;", Tone.DANGER,
                    "Se canceló la cita del Dr(a). " + esc(doctorName(a)) + ".", receiptPdf, null, null);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendCancellationNoticeToAdmins(Appointment a, byte[] receiptPdf) {
        for (User u : userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN)) {
            staffNotice(a, u.getEmail(), Audience.ADMIN, "Cita cancelada", "&#10005;", Tone.DANGER,
                    "Se canceló la cita del Dr(a). " + esc(doctorName(a)) + ".", receiptPdf, null, null);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendRescheduleNoticeToDoctor(Appointment a, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf) {
        staffNotice(a, a.getDoctor().getUser().getEmail(), Audience.DOCTOR, "Cita reprogramada", "&#8635;", Tone.INFO,
                "La cita con <strong>" + esc(patientName(a)) + "</strong> cambió de horario.", receiptPdf, oldDate, oldStartTime);
    }

    @Override
    @Async("notificationExecutor")
    public void sendRescheduleNoticeToReceptionists(Appointment a, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf) {
        for (User u : userRepository.findByRole_NameAndIsActiveTrue(RoleName.RECEPTIONIST)) {
            staffNotice(a, u.getEmail(), Audience.RECEPTION, "Cita reprogramada", "&#8635;", Tone.INFO,
                    "Se reprogramó la cita del Dr(a). " + esc(doctorName(a)) + ".", receiptPdf, oldDate, oldStartTime);
        }
    }

    @Override
    @Async("notificationExecutor")
    public void sendRescheduleNoticeToAdmins(Appointment a, LocalDate oldDate, LocalTime oldStartTime, byte[] receiptPdf) {
        for (User u : userRepository.findByRole_NameAndIsActiveTrue(RoleName.ADMIN)) {
            staffNotice(a, u.getEmail(), Audience.ADMIN, "Cita reprogramada", "&#8635;", Tone.INFO,
                    "Se reprogramó la cita del Dr(a). " + esc(doctorName(a)) + ".", receiptPdf, oldDate, oldStartTime);
        }
    }

    /** Aviso operativo al personal: lo que necesita para preparar la consulta y un botón al panel. */
    private void staffNotice(Appointment a, String to, Audience audience, String title, String icon, Tone tone,
                             String intro, byte[] receiptPdf, LocalDate oldDate, LocalTime oldTime) {
        if (blank(to)) return;
        deliver(to, title + " · " + a.getAppointmentDate().format(DATE_FMT) + " " + a.getStartTime().format(TIME_FMT), a.getId(), receiptPdf, h -> {
            EmailTemplate t = new EmailTemplate(h.getPrimaryColor())
                    .preheader(patientName(a) + " · " + a.getAppointmentDate().format(LONG_DATE) + " " + a.getStartTime().format(TIME_FMT))
                    .hero(icon, tone, "Aviso del sistema", title, intro);
            if (oldDate != null) t.change(oldDate, oldTime, a);
            t.appointmentCard(a, tone == Tone.DANGER);
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Paciente", esc(patientName(a))});
            rows.add(new String[]{"Teléfono", esc(a.getPatient().getPhone())});
            rows.add(new String[]{"Folio", "#" + String.format("%06d", a.getId())});
            if (audience == Audience.DOCTOR) {
                rows.add(new String[]{"Alergias", esc(blank(a.getAllergiesSnapshot()) ? "No especificadas" : a.getAllergiesSnapshot())});
                rows.add(new String[]{"Motivo", esc(a.getReasonForVisit())});
            }
            if (tone == Tone.DANGER) rows.add(new String[]{"Motivo de cancelación", esc(a.getCancelReason())});
            if (Boolean.TRUE.equals(a.getOverbooked())) rows.add(new String[]{"Tipo", "Sobrecupo"});
            return t.rows(rows)
                    .button("Abrir en el panel", tenantLinks.frontendBase() + switch (audience) {
                        case DOCTOR -> "/doctor/citas";
                        case RECEPTION -> "/recepcion/citas";
                        case ADMIN -> "/admin/citas";
                    })
                    .build(h.getName(), logoUrl(h), contact(h), "Recibes este aviso por ser parte del personal de " + h.getName() + ".");
        });
    }

    // ── Genérico (ARCO) ─────────────────────────────────────────

    @Override
    @Async("notificationExecutor")
    public void sendHtml(String to, String subject, String bodyHtml) {
        deliver(to, subject, null, null, h -> new EmailTemplate(h.getPrimaryColor())
                .preheader(subject)
                .hero("&#128737;", Tone.BRAND, "Datos personales", subject, "")
                .paragraph(bodyHtml)
                .build(h.getName(), logoUrl(h), contact(h), "Recibes este correo por una solicitud de derechos ARCO."));
    }

    @Override
    @Async("notificationExecutor")
    public void sendPrivacy(String to, String subject, String title, String introHtml, List<String[]> rows,
                            String buttonLabel, String buttonUrl, String noteHtml, String attachmentName, byte[] attachment) {
        deliverWithAttachment(to, subject, attachmentName, attachment, h -> {
            EmailTemplate t = new EmailTemplate(h.getPrimaryColor())
                    .preheader(title)
                    .hero("&#128737;", Tone.BRAND, "Datos personales", title, introHtml);
            if (rows != null && !rows.isEmpty()) t.rows(rows);
            if (buttonLabel != null && buttonUrl != null) t.button(buttonLabel, buttonUrl);
            if (noteHtml != null) t.notice(Tone.NEUTRAL, "Importante", noteHtml);
            return t.build(h.getName(), logoUrl(h), contact(h),
                    "Recibes este correo por una solicitud sobre tus datos personales en " + h.getName() + ".");
        });
    }

    /** Escapa texto capturado por usuarios antes de insertarlo en un correo HTML. */
    public static String escapeHtml(String value) {
        return esc(value);
    }

    // ── Envío ───────────────────────────────────────────────────

    private interface Body {
        String render(HospitalConfig hospital) throws Exception;
    }

    private void deliver(String to, String subject, Long appointmentId, byte[] receiptPdf, Body body) {
        deliverWithAttachment(to, subject, appointmentId != null ? "comprobante-cita-" + appointmentId + ".pdf" : null,
                appointmentId != null ? receiptPdf : null, body);
    }

    private void deliverWithAttachment(String to, String subject, String filename, byte[] attachment, Body body) {
        if (blank(to)) return;
        EmailConfig mailConfig = emailConfigService.getEntity();
        if (!Boolean.TRUE.equals(mailConfig.getEnabled())) {
            log.info("Envío de correo deshabilitado; se omite '{}' a {}", subject, to);
            return;
        }
        try {
            HospitalConfig hospital = hospitalConfigService.getEntity();
            String html = body.render(hospital);
            send(mailConfig, to, subject + " — " + hospital.getName(), html, filename, attachment);
            log.info("Correo '{}' enviado a {}", subject, to);
        } catch (Exception e) {
            log.error("No se pudo enviar '{}' a {}", subject, to, e);
        }
    }

    private void send(EmailConfig mailConfig, String to, String subject, String html, String attachmentFilename,
                      byte[] attachmentPdf) throws Exception {
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
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setTo(to);
        String from = !blank(mailConfig.getFromAddress()) ? mailConfig.getFromAddress() : mailConfig.getSmtpUsername();
        helper.setFrom(from);
        helper.setSubject(subject);
        helper.setText(EmailTemplate.toPlainText(html), html);
        if (attachmentPdf != null && attachmentFilename != null) {
            helper.addAttachment(attachmentFilename, new ByteArrayResource(attachmentPdf), "application/pdf");
        }
        sender.send(message);
    }

    // ── Datos ───────────────────────────────────────────────────

    private List<String[]> appointmentRows(Appointment a) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Folio", "#" + String.format("%06d", a.getId())});
        rows.add(new String[]{"Paciente", esc(patientName(a))});
        BigDecimal price = a.getPrice();
        rows.add(new String[]{"Consulta", price != null && price.signum() > 0
                ? NumberFormat.getCurrencyInstance(MX).format(price) + " MXN" : "Por confirmar en recepción"});
        String ps = a.getPaymentStatus() != null ? a.getPaymentStatus().name() : "UNPAID";
        rows.add(new String[]{"Pago", switch (ps) {
            case "PAID" -> "<span style=\"color:#047857;\">Pagada</span>";
            case "PENDING" -> "<span style=\"color:#1d4ed8;\">Transferencia pendiente</span>";
            case "REFUNDED" -> "Reembolsada";
            default -> "En recepción, al terminar tu consulta";
        }});
        return rows;
    }

    private List<String> recommendations(Appointment a) {
        String raw = !blank(a.getDoctor().getSpecialty().getRecommendations())
                ? a.getDoctor().getSpecialty().getRecommendations() : DEFAULT_RECOMMENDATIONS;
        List<String> items = new ArrayList<>();
        for (String part : raw.split("[•\\n]")) {
            if (!part.isBlank()) items.add(part.trim());
        }
        return items;
    }

    private String manageUrl(Appointment a) {
        return tenantLinks.frontendBase() + "/cancelar-cita/" + a.getCancelToken();
    }

    /** El logo solo se muestra si hay una URL pública del backend para que el cliente de correo lo descargue. */
    private String logoUrl(HospitalConfig h) {
        String logo = h.getLogoUrl();
        if (blank(logo)) return null;
        if (logo.startsWith("http://") || logo.startsWith("https://")) return logo;
        if (blank(publicUrl)) return null;
        return publicUrl.replaceAll("/+$", "") + "/uploads/" + logo.replaceAll("^/+", "");
    }

    private String contact(HospitalConfig h) {
        List<String> parts = new ArrayList<>();
        if (!blank(h.getContactPhone())) parts.add("&#9742; " + esc(h.getContactPhone()));
        if (!blank(h.getContactEmail())) parts.add("&#9993; " + esc(h.getContactEmail()));
        String line = String.join("&nbsp;&nbsp;·&nbsp;&nbsp;", parts);
        String address = AppointmentReceiptServiceImpl.hospitalAddress(h);
        return address.isEmpty() ? line : line + "<br>&#128205; " + esc(address);
    }

    private static String patientName(Appointment a) {
        return a.getPatient().getFirstName() + " " + a.getPatient().getLastName();
    }

    private static String doctorName(Appointment a) {
        return a.getDoctor().getUser().getFirstName() + " " + a.getDoctor().getUser().getLastName();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
