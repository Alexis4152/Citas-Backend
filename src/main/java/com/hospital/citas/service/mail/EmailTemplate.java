package com.hospital.citas.service.mail;

import com.hospital.citas.entity.Appointment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Constructor de correos HTML con el diseño de la plataforma (patrón de los correos de hotel):
 * encabezado oscuro con la marca del hospital, encabezado de estado con ícono, tarjeta de la
 * cita tipo boleto, filas de datos, botón de acción, avisos y pie con el contacto del hospital.
 * <p>
 * Todo con tablas y estilos en línea: es lo único que Gmail, Outlook y Apple Mail respetan por
 * igual. Cualquier texto que venga del usuario debe pasar por {@link #esc}.
 */
public final class EmailTemplate {

    public enum Tone {
        SUCCESS("#047857", "#ecfdf5"), DANGER("#b91c1c", "#fef2f2"), WARNING("#b45309", "#fffbeb"),
        INFO("#1d4ed8", "#eff6ff"), BRAND(null, null), NEUTRAL("#334155", "#f1f5f9");

        final String fg;
        final String bg;

        Tone(String fg, String bg) {
            this.fg = fg;
            this.bg = bg;
        }
    }

    private static final Locale MX = Locale.forLanguageTag("es-MX");
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", MX);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM", MX);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", MX);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    private final String brand;
    private final StringBuilder body = new StringBuilder();
    private String preheader = "";

    public EmailTemplate(String brandColor) {
        this.brand = brandColor != null && brandColor.matches("^#[0-9A-Fa-f]{6}$") ? brandColor : "#155dea";
    }

    public EmailTemplate preheader(String text) {
        this.preheader = text;
        return this;
    }

    /** Ícono en círculo de color + etiqueta + título + párrafo de entrada. */
    public EmailTemplate hero(String icon, Tone tone, String eyebrow, String title, String introHtml) {
        String fg = tone.fg != null ? tone.fg : brand;
        String bg = tone.bg != null ? tone.bg : tint(brand);
        body.append("""
                <tr><td style="padding:36px 36px 8px;">
                  <table role="presentation" cellpadding="0" cellspacing="0"><tr>
                    <td style="width:52px;height:52px;border-radius:16px;background:%s;color:%s;font-size:24px;font-weight:700;text-align:center;line-height:52px;">%s</td>
                  </tr></table>
                  <p style="margin:22px 0 6px;font-size:11px;font-weight:700;letter-spacing:1.6px;text-transform:uppercase;color:%s;">%s</p>
                  <h1 style="margin:0 0 12px;font-size:26px;line-height:1.25;color:#0a0f1c;font-weight:700;">%s</h1>
                  <p style="margin:0;font-size:15px;line-height:1.6;color:#475569;">%s</p>
                </td></tr>
                """.formatted(bg, fg, icon, fg, esc(eyebrow), esc(title), introHtml));
        return this;
    }

    /** Tarjeta tipo boleto con fecha grande, hora, doctor y sede. {@code struck}: cita cancelada. */
    public EmailTemplate appointmentCard(Appointment a, boolean struck) {
        LocalDate d = a.getAppointmentDate();
        String deco = struck ? "text-decoration:line-through;opacity:.55;" : "";
        String address = join(", ", a.getBranch().getAddress(), a.getBranch().getCity());
        body.append("""
                <tr><td style="padding:24px 36px 4px;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border:1px solid #e2e8f0;border-radius:18px;border-collapse:separate;">
                    <tr>
                      <td width="104" valign="middle" style="background:%s;border-radius:17px 0 0 17px;text-align:center;padding:18px 8px;%s">
                        <div style="font-size:12px;font-weight:700;letter-spacing:1px;text-transform:uppercase;color:rgba(255,255,255,.8);">%s</div>
                        <div style="font-size:40px;line-height:1.05;font-weight:800;color:#ffffff;">%d</div>
                        <div style="font-size:12px;color:rgba(255,255,255,.85);text-transform:capitalize;">%s</div>
                      </td>
                      <td valign="middle" style="padding:18px 22px;%s">
                        <div style="font-size:20px;font-weight:700;color:#0a0f1c;">%s – %s <span style="font-size:13px;font-weight:600;color:#64748b;">h</span></div>
                        <div style="margin-top:6px;font-size:15px;font-weight:600;color:#1e293b;">Dr(a). %s %s</div>
                        <div style="font-size:13px;color:%s;font-weight:600;">%s</div>
                        <div style="margin-top:10px;font-size:13px;color:#475569;">&#128205; %s%s</div>
                      </td>
                    </tr>
                  </table>
                </td></tr>
                """.formatted(brand, deco, d.format(MONTH).replace(".", ""), d.getDayOfMonth(), d.format(WEEKDAY), deco,
                a.getStartTime().format(HOUR), a.getEndTime().format(HOUR),
                esc(a.getDoctor().getUser().getFirstName()), esc(a.getDoctor().getUser().getLastName()),
                brand, esc(a.getDoctor().getSpecialty().getName()),
                esc(a.getBranch().getName()), address.isEmpty() ? "" : " · " + esc(address)));
        return this;
    }

    /** Antes → después, para las reprogramaciones. */
    public EmailTemplate change(LocalDate oldDate, LocalTime oldTime, Appointment now) {
        body.append("""
                <tr><td style="padding:20px 36px 0;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"><tr>
                    <td width="48%%" style="background:#f8fafc;border-radius:14px;padding:14px 16px;">
                      <div style="font-size:10px;font-weight:700;letter-spacing:1.3px;color:#94a3b8;text-transform:uppercase;">Antes</div>
                      <div style="margin-top:4px;font-size:14px;color:#94a3b8;text-decoration:line-through;text-transform:capitalize;">%s · %s h</div>
                    </td>
                    <td width="4%%" style="text-align:center;font-size:18px;color:%s;">&#8594;</td>
                    <td width="48%%" style="background:%s;border-radius:14px;padding:14px 16px;">
                      <div style="font-size:10px;font-weight:700;letter-spacing:1.3px;color:%s;text-transform:uppercase;">Ahora</div>
                      <div style="margin-top:4px;font-size:14px;font-weight:700;color:#0a0f1c;text-transform:capitalize;">%s · %s h</div>
                    </td>
                  </tr></table>
                </td></tr>
                """.formatted(oldDate.format(LONG), oldTime.format(HOUR), brand, tint(brand), brand,
                now.getAppointmentDate().format(LONG), now.getStartTime().format(HOUR)));
        return this;
    }

    /** Filas etiqueta / valor (valores ya escapados si vienen del usuario). */
    public EmailTemplate rows(List<String[]> rows) {
        StringBuilder r = new StringBuilder();
        for (String[] row : rows) {
            if (row[1] == null || row[1].isBlank()) continue;
            r.append("""
                    <tr>
                      <td style="padding:11px 0;border-bottom:1px solid #f1f5f9;font-size:13px;color:#64748b;width:42%%;">%s</td>
                      <td style="padding:11px 0;border-bottom:1px solid #f1f5f9;font-size:14px;color:#0f172a;font-weight:600;text-align:right;">%s</td>
                    </tr>
                    """.formatted(esc(row[0]), row[1]));
        }
        body.append("""
                <tr><td style="padding:18px 36px 0;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">%s</table>
                </td></tr>
                """.formatted(r));
        return this;
    }

    public EmailTemplate button(String label, String url) {
        body.append("""
                <tr><td style="padding:28px 36px 4px;">
                  <table role="presentation" cellpadding="0" cellspacing="0"><tr>
                    <td style="border-radius:14px;background:%s;">
                      <a href="%s" style="display:inline-block;padding:14px 26px;font-size:15px;font-weight:700;color:#ffffff;text-decoration:none;border-radius:14px;">%s &#8594;</a>
                    </td>
                  </tr></table>
                </td></tr>
                """.formatted(brand, escAttr(url), esc(label)));
        return this;
    }

    /** Enlaces secundarios en una línea (agregar al calendario, cómo llegar...). */
    public EmailTemplate links(List<String[]> links) {
        StringBuilder sb = new StringBuilder();
        for (String[] l : links) {
            if (!sb.isEmpty()) sb.append("<span style=\"color:#cbd5e1;\">&nbsp;·&nbsp;</span>");
            sb.append("<a href=\"").append(escAttr(l[1])).append("\" style=\"color:").append(brand)
                    .append(";font-weight:600;text-decoration:none;font-size:13px;\">").append(esc(l[0])).append("</a>");
        }
        body.append("<tr><td style=\"padding:14px 36px 0;\">").append(sb).append("</td></tr>");
        return this;
    }

    public EmailTemplate notice(Tone tone, String titleText, String html) {
        String fg = tone.fg != null ? tone.fg : brand;
        String bg = tone.bg != null ? tone.bg : tint(brand);
        body.append("""
                <tr><td style="padding:22px 36px 0;">
                  <div style="background:%s;border-left:4px solid %s;border-radius:12px;padding:14px 18px;">
                    %s<div style="font-size:13px;line-height:1.6;color:#334155;">%s</div>
                  </div>
                </td></tr>
                """.formatted(bg, fg, titleText == null ? ""
                : "<div style=\"font-size:13px;font-weight:700;color:" + fg + ";margin-bottom:4px;\">" + esc(titleText) + "</div>", html));
        return this;
    }

    public EmailTemplate checklist(String titleText, List<String> items) {
        if (items.isEmpty()) return this;
        StringBuilder li = new StringBuilder();
        for (String item : items) {
            li.append("""
                    <tr><td valign="top" style="padding:5px 10px 5px 0;width:22px;">
                      <div style="width:20px;height:20px;border-radius:10px;background:%s;color:%s;font-size:12px;font-weight:700;text-align:center;line-height:20px;">&#10003;</div>
                    </td><td style="padding:5px 0;font-size:14px;line-height:1.5;color:#334155;">%s</td></tr>
                    """.formatted(tint(brand), brand, esc(item)));
        }
        body.append("""
                <tr><td style="padding:26px 36px 0;">
                  <p style="margin:0 0 8px;font-size:11px;font-weight:700;letter-spacing:1.4px;text-transform:uppercase;color:#64748b;">%s</p>
                  <table role="presentation" cellpadding="0" cellspacing="0">%s</table>
                </td></tr>
                """.formatted(esc(titleText), li));
        return this;
    }

    public EmailTemplate paragraph(String html) {
        body.append("<tr><td style=\"padding:18px 36px 0;font-size:14px;line-height:1.6;color:#475569;\">").append(html).append("</td></tr>");
        return this;
    }

    /** Bloque con un valor destacado en tipografía monoespaciada (contraseña temporal, folio). */
    public EmailTemplate code(String labelText, String value) {
        body.append("""
                <tr><td style="padding:18px 36px 0;">
                  <div style="background:#0a0f1c;border-radius:14px;padding:16px 20px;">
                    <div style="font-size:10px;font-weight:700;letter-spacing:1.4px;text-transform:uppercase;color:#94a3b8;">%s</div>
                    <div style="margin-top:6px;font-family:'SFMono-Regular',Consolas,monospace;font-size:20px;font-weight:700;letter-spacing:1px;color:#ffffff;">%s</div>
                  </div>
                </td></tr>
                """.formatted(esc(labelText), esc(value)));
        return this;
    }

    /** Arma el documento completo con el encabezado y el pie del hospital. */
    public String build(String hospitalName, String logoAbsoluteUrl, String contactLine, String whyYouGetThis) {
        String logo = logoAbsoluteUrl != null
                ? "<img src=\"" + escAttr(logoAbsoluteUrl) + "\" width=\"44\" height=\"44\" alt=\"\" style=\"display:block;border-radius:12px;background:#ffffff;object-fit:contain;\">"
                : "<div style=\"width:44px;height:44px;border-radius:12px;background:" + brand
                + ";color:#ffffff;font-size:17px;font-weight:800;text-align:center;line-height:44px;\">" + esc(initials(hospitalName)) + "</div>";
        return """
                <!doctype html>
                <html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="color-scheme" content="light"><title>%s</title></head>
                <body style="margin:0;padding:0;background:#eef2f7;font-family:%s;">
                <div style="display:none;max-height:0;overflow:hidden;opacity:0;">%s</div>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#eef2f7;">
                <tr><td align="center" style="padding:32px 12px;">
                  <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="width:100%%;max-width:600px;">
                    <tr><td style="background:#0a0f1c;border-radius:22px 22px 0 0;padding:24px 36px;border-bottom:4px solid %s;">
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"><tr>
                        <td width="56" valign="middle">%s</td>
                        <td valign="middle" style="padding-left:14px;">
                          <div style="font-size:17px;font-weight:700;color:#ffffff;">%s</div>
                          <div style="font-size:12px;color:#94a3b8;">Citas en línea</div>
                        </td>
                      </tr></table>
                    </td></tr>
                    <tr><td style="background:#ffffff;">
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">%s
                        <tr><td style="padding:32px 36px 0;"></td></tr>
                      </table>
                    </td></tr>
                    <tr><td style="background:#f8fafc;border-radius:0 0 22px 22px;padding:22px 36px;border-top:1px solid #e2e8f0;">
                      <div style="font-size:13px;font-weight:700;color:#334155;">%s</div>
                      <div style="margin-top:4px;font-size:12px;color:#64748b;">%s</div>
                      <div style="margin-top:12px;font-size:11px;line-height:1.5;color:#94a3b8;">%s</div>
                    </td></tr>
                    <tr><td style="padding:18px 0;text-align:center;font-size:11px;color:#94a3b8;">Enviado con Nexora Citas</td></tr>
                  </table>
                </td></tr></table>
                </body></html>
                """.formatted(esc(hospitalName), FONT, esc(preheader), brand, logo, esc(hospitalName), body,
                esc(hospitalName), contactLine == null ? "" : contactLine, esc(whyYouGetThis));
    }

    /** Versión en texto plano (algunos clientes y filtros de spam la usan). */
    public static String toPlainText(String html) {
        return html.replaceAll("(?s)<head>.*?</head>", "")
                .replaceAll("(?s)<div style=\"display:none[^>]*>.*?</div>", "")
                .replaceAll("(?i)<br\\s*/?>|</(p|div|tr|h1|li)>", "\n")
                .replaceAll("<a [^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", "$2 ($1)")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&#8594;", "->").replace("&#10003;", "-").replace("&#128205;", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").trim();
    }

    // ── Enlaces útiles ──────────────────────────────────────────

    /** "Agregar a Google Calendar" con la cita (hora local del hospital). */
    public static String googleCalendarUrl(Appointment a, String hospitalName) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        String start = a.getAppointmentDate().atTime(a.getStartTime()).format(f);
        String end = a.getAppointmentDate().atTime(a.getEndTime()).format(f);
        String title = "Cita con Dr(a). " + a.getDoctor().getUser().getLastName() + " - " + hospitalName;
        String location = join(", ", a.getBranch().getName(), a.getBranch().getAddress(), a.getBranch().getCity());
        return "https://calendar.google.com/calendar/render?action=TEMPLATE&text=" + enc(title) + "&dates=" + start + "/" + end
                + "&ctz=America/Mexico_City&location=" + enc(location) + "&details=" + enc(a.getDoctor().getSpecialty().getName());
    }

    public static String mapsUrl(Appointment a) {
        return "https://www.google.com/maps/search/?api=1&query="
                + enc(join(", ", a.getBranch().getName(), a.getBranch().getAddress(), a.getBranch().getCity()));
    }

    // ── Utilidades ──────────────────────────────────────────────

    public static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escAttr(String url) {
        return esc(url);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) sb.append(sep);
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "H";
        StringBuilder sb = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (w.length() > 2 && sb.length() < 2) sb.append(Character.toUpperCase(w.charAt(0)));
        }
        return sb.isEmpty() ? name.substring(0, 1).toUpperCase() : sb.toString();
    }

    /** Tono muy claro del color de marca (fondos de avisos y chips). */
    private static String tint(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return String.format("#%02x%02x%02x", r + (255 - r) * 88 / 100, g + (255 - g) * 88 / 100, b + (255 - b) * 88 / 100);
    }
}
