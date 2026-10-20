package com.hospital.citas.service;

import com.hospital.citas.entity.ArcoRequest;
import com.hospital.citas.tenant.TenantLinks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.hospital.citas.service.impl.EmailServiceImpl.escapeHtml;

/** Correos de solicitudes ARCO: acuse a quien la pidió, aviso a los admins y resolución. */
@Service
@RequiredArgsConstructor
public class ArcoEmailService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final EmailService emailService;
    private final TenantLinks tenantLinks;

    public void sendReceivedToRequester(ArcoRequest arco) {
        String body = """
                <p>Hola %s, recibimos tu solicitud de <strong>%s</strong> sobre tus datos personales.</p>
                <ul>
                  <li><strong>Folio:</strong> %s</li>
                  <li><strong>Fecha límite de respuesta:</strong> %s (20 días hábiles)</li>
                </ul>
                <p>Te responderemos a este correo. Si tienes cuenta, también puedes ver el seguimiento en
                <a href="%s">Mis solicitudes ARCO</a>.</p>
                <p style="color:#6b7280;font-size:13px;">Si no hiciste esta solicitud, responde a este correo para avisarnos.</p>
                """.formatted(escapeHtml(arco.getRequesterName()), arco.getType().label(), ArcoRequestService.folio(arco),
                arco.getDueDate().format(DATE), tenantLinks.frontendBase() + "/mis-solicitudes-arco");
        emailService.sendHtml(arco.getRequesterEmail(), "Recibimos tu solicitud ARCO " + ArcoRequestService.folio(arco), body);
    }

    public void sendNoticeToAdmins(ArcoRequest arco, List<String> adminEmails) {
        String body = """
                <p>Llegó una solicitud de derechos ARCO que hay que atender antes del <strong>%s</strong>.</p>
                <ul>
                  <li><strong>Folio:</strong> %s</li>
                  <li><strong>Tipo:</strong> %s</li>
                  <li><strong>Nombre:</strong> %s</li>
                  <li><strong>Correo:</strong> %s</li>
                  <li><strong>Teléfono:</strong> %s</li>
                </ul>
                <p><strong>Detalle:</strong><br>%s</p>
                <p><a href="%s">Atender en el panel</a></p>
                """.formatted(arco.getDueDate().format(DATE), ArcoRequestService.folio(arco), arco.getType().label(),
                escapeHtml(arco.getRequesterName()), escapeHtml(arco.getRequesterEmail()),
                escapeHtml(arco.getRequesterPhone() != null ? arco.getRequesterPhone() : "—"),
                arco.getDetails() != null ? escapeHtml(arco.getDetails()).replace("\n", "<br>") : "—",
                tenantLinks.frontendBase() + "/admin/solicitudes-arco");
        for (String to : adminEmails) {
            emailService.sendHtml(to, "Nueva solicitud ARCO (" + arco.getType().label() + ")", body);
        }
    }

    public void sendResolvedToRequester(ArcoRequest arco) {
        String body = """
                <p>Hola %s, tu solicitud <strong>%s</strong> (%s) fue <strong>%s</strong>.</p>
                <p><strong>Respuesta del hospital:</strong><br>%s</p>
                """.formatted(escapeHtml(arco.getRequesterName()), ArcoRequestService.folio(arco), arco.getType().label(),
                arco.getStatus().label().toLowerCase(), escapeHtml(arco.getResolutionNotes()).replace("\n", "<br>"));
        emailService.sendHtml(arco.getRequesterEmail(), "Respuesta a tu solicitud ARCO " + ArcoRequestService.folio(arco), body);
    }
}
