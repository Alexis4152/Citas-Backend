package com.hospital.citas.service;

import com.hospital.citas.entity.ArcoRequest;
import com.hospital.citas.tenant.TenantLinks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.hospital.citas.service.impl.EmailServiceImpl.escapeHtml;

/** Correos de las solicitudes ARCO. Todo enlace lleva al portal de seguimiento de la solicitud. */
@Service
@RequiredArgsConstructor
public class ArcoEmailService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final EmailService emailService;
    private final TenantLinks tenantLinks;

    public String trackingUrl(String token) {
        return tenantLinks.frontendBase() + "/derechos-arco/seguimiento/" + token;
    }

    private List<String[]> basics(ArcoRequest a) {
        return List.of(new String[]{"Folio", ArcoRequestService.folio(a)}, new String[]{"Derecho", a.getType().label()});
    }

    /** Paso 1 (formulario público): confirmar que el correo es suyo. */
    public void sendConfirmEmail(ArcoRequest a, String token) {
        emailService.sendPrivacy(a.getRequesterEmail(), "Confirma tu solicitud " + ArcoRequestService.folio(a),
                "Confirma tu solicitud", "Hola " + escapeHtml(a.getRequesterName()) + ", para atender tu solicitud de <strong>"
                        + a.getType().label() + "</strong> necesitamos confirmar que este correo es tuyo.",
                basics(a), "Confirmar mi solicitud", trackingUrl(token) + "?confirmar=1",
                "Si no hiciste esta solicitud, ignora este correo: sin confirmar no se atiende y se borra en 3 días.", null, null);
    }

    public void sendReceived(ArcoRequest a, String token) {
        if (blank(a.getRequesterEmail())) return;
        emailService.sendPrivacy(a.getRequesterEmail(), "Recibimos tu solicitud " + ArcoRequestService.folio(a),
                "Recibimos tu solicitud", "Hola " + escapeHtml(a.getRequesterName()) + ", tu solicitud quedó registrada. "
                        + "Te responderemos a más tardar el <strong>" + a.getDueDate().format(DATE) + "</strong> (20 días hábiles).",
                basics(a), token == null ? null : "Ver el seguimiento", token == null ? null : trackingUrl(token),
                identityHint(a), null, null);
    }

    public void sendNoticeToAdmins(ArcoRequest a, List<String> adminEmails) {
        for (String to : adminEmails) {
            emailService.sendPrivacy(to, "Nueva solicitud ARCO " + ArcoRequestService.folio(a),
                    "Hay una solicitud ARCO por atender", "Llegó una solicitud de <strong>" + a.getType().label()
                            + "</strong> de " + escapeHtml(a.getRequesterName()) + ". Hay que responderla antes del <strong>"
                            + a.getDueDate().format(DATE) + "</strong>.",
                    basics(a), "Abrir en el panel", tenantLinks.frontendBase() + "/admin/solicitudes-arco/" + a.getId(), null, null, null);
        }
    }

    public void sendInfoRequested(ArcoRequest a, String token) {
        if (blank(a.getRequesterEmail())) return;
        emailService.sendPrivacy(a.getRequesterEmail(), "Necesitamos información para tu solicitud " + ArcoRequestService.folio(a),
                "Necesitamos algo más", "Para continuar con tu solicitud necesitamos lo siguiente:<br><br><em>"
                        + escapeHtml(a.getInfoRequest()).replace("\n", "<br>") + "</em>",
                basics(a), token == null ? null : "Responder", token == null ? null : trackingUrl(token),
                "Tienes hasta el <strong>" + a.getInfoDue().format(DATE) + "</strong> (10 días hábiles). Si no respondes, la solicitud se tendrá por no presentada.",
                null, null);
    }

    public void sendExtended(ArcoRequest a, String token) {
        if (blank(a.getRequesterEmail())) return;
        emailService.sendPrivacy(a.getRequesterEmail(), "Ampliamos el plazo de tu solicitud " + ArcoRequestService.folio(a),
                "Necesitamos un poco más de tiempo", "Por la razón siguiente ampliamos una sola vez el plazo para responderte: <em>"
                        + escapeHtml(a.getExtensionReason()) + "</em>. Nueva fecha límite: <strong>" + a.getDueDate().format(DATE) + "</strong>.",
                basics(a), token == null ? null : "Ver el seguimiento", token == null ? null : trackingUrl(token), null, null, null);
    }

    /** Respuesta formal (procede, procede en parte o no procede), con la constancia en PDF. */
    public void sendAnswer(ArcoRequest a, String token, byte[] constancia) {
        if (blank(a.getRequesterEmail())) return;
        boolean rejected = "IMPROCEDENTE".equals(a.getOutcome());
        String intro = rejected
                ? "Revisamos tu solicitud y <strong>no procede</strong>. Fundamento: " + escapeHtml(a.getLegalBasis())
                : "Tu solicitud <strong>" + ("PARCIAL".equals(a.getOutcome()) ? "procede en parte" : "procede")
                  + "</strong>. La haremos efectiva a más tardar el <strong>" + a.getExecutionDue().format(DATE) + "</strong>.";
        if (!blank(a.getResolutionNotes())) intro += "<br><br>" + escapeHtml(a.getResolutionNotes()).replace("\n", "<br>");
        emailService.sendPrivacy(a.getRequesterEmail(), "Respuesta a tu solicitud " + ArcoRequestService.folio(a),
                "Respuesta a tu solicitud", intro, basics(a), token == null ? null : "Ver el seguimiento", token == null ? null : trackingUrl(token),
                "Si no estás de acuerdo con la respuesta, puedes acudir a la Secretaría Anticorrupción y Buen Gobierno.",
                "constancia-" + ArcoRequestService.folio(a) + ".pdf", constancia);
    }

    public void sendExecuted(ArcoRequest a, String token, byte[] constancia) {
        if (blank(a.getRequesterEmail())) return;
        String extra = a.getType() == com.hospital.citas.enums.ArcoRequestType.ACCESS
                ? "Tu reporte de datos personales está listo: descárgalo desde el seguimiento de tu solicitud (el enlace es personal)."
                : "Esto es lo que hicimos:<br>" + escapeHtml(a.getExecutionSummary()).replace("\n", "<br>");
        emailService.sendPrivacy(a.getRequesterEmail(), "Atendimos tu solicitud " + ArcoRequestService.folio(a),
                "Tu solicitud quedó atendida", extra, basics(a), token == null ? null : "Ver el seguimiento",
                token == null ? null : trackingUrl(token), null, "constancia-" + ArcoRequestService.folio(a) + ".pdf", constancia);
    }

    public void sendNotFiled(ArcoRequest a) {
        if (blank(a.getRequesterEmail())) return;
        emailService.sendPrivacy(a.getRequesterEmail(), "Tu solicitud " + ArcoRequestService.folio(a) + " se cerró",
                "Tu solicitud se cerró", "No recibimos la información que te pedimos dentro del plazo, así que la solicitud se tiene por "
                        + "no presentada. Puedes volver a hacerla cuando quieras.", basics(a), null, null, null, null, null);
    }

    /** Activar la cuenta cuando ya existía un expediente con ese correo. */
    public void sendAccountActivation(String to, String firstName, String token) {
        emailService.sendPrivacy(to, "Activa tu cuenta", "Activa tu cuenta",
                "Hola " + escapeHtml(firstName) + ", ya tenemos un expediente con este correo. Para proteger tus datos, confirma que "
                        + "este correo es tuyo y ligaremos tu cuenta nueva a tu expediente.",
                null, "Activar mi cuenta", tenantLinks.frontendBase() + "/activar-cuenta/" + token,
                "El enlace vence en 48 horas. Si no creaste una cuenta, ignora este correo.", null, null);
    }

    private static String identityHint(ArcoRequest a) {
        if (a.getIdentityVerifiedAt() != null || !a.getType().needsStrongIdentity()) return null;
        return "Para proteger tus datos necesitamos verificar tu identidad: sube una identificación oficial vigente desde el seguimiento "
                + "o preséntala en recepción." + (Boolean.TRUE.equals(a.getRepresentative())
                ? " Como representante, sube también el documento que acredite la representación." : "");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
