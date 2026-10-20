package com.hospital.citas.service.impl;

import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.Branch;
import com.hospital.citas.entity.HospitalConfig;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.BloodType;
import com.hospital.citas.pdf.LogoLoader;
import com.hospital.citas.service.AppointmentReceiptService;
import com.hospital.citas.service.HospitalConfigService;
import com.hospital.citas.tenant.TenantLinks;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.hospital.citas.pdf.PdfKit.*;

/**
 * Comprobante de cita en PDF con diseño de "pase" (estilo de los comprobantes de hotel):
 * encabezado oscuro con la marca del hospital, boleto con la fecha y hora en grande y el QR de
 * llegada en un talón perforado, y tarjetas con paciente, sede, información médica, pago y
 * recomendaciones. Si la cita está cancelada se marca con sello de agua y el motivo.
 * Todo en memoria (byte[]); el logo es opcional (sin él van las iniciales del hospital).
 */
@Service
@RequiredArgsConstructor
public class AppointmentReceiptServiceImpl implements AppointmentReceiptService {

    private static final Locale MX = Locale.forLanguageTag("es-MX");
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", MX);
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", MX);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final float W = PDRectangle.A4.getWidth();
    private static final float H = PDRectangle.A4.getHeight();
    private static final float M = 40;
    private static final float CW = W - M * 2;

    private static final String DEFAULT_RECOMMENDATIONS = "Llega 15 minutos antes de tu cita • Trae una identificación oficial "
            + "• Si no vas a poder asistir, cancela con anticipación para liberar el espacio";

    private final HospitalConfigService hospitalConfigService;
    private final LogoLoader logoLoader;
    private final TenantLinks tenantLinks;

    @Override
    public byte[] build(Appointment appointment) {
        HospitalConfig hospital = hospitalConfigService.getEntity();
        int[] brand = hexToRgb(hospital.getPrimaryColor());
        try (PDDocument doc = new PDDocument()) {
            PDPageContentStream c = newPage(doc);
            try {
                float y = header(c, doc, hospital, brand, appointment);
                y = ticket(c, doc, appointment, brand, y - 22);
                y = people(c, appointment, y - 14);
                y = medical(c, appointment, y - 14);
                y = payment(c, appointment, brand, y - 14);
                // Lo que no quepa arriba del pie (recomendaciones largas) sigue en otra hoja.
                if (y - 14 - closingHeight(appointment) < FOOTER_TOP) {
                    footer(c, hospital, 1);
                    c.close();
                    c = newPage(doc);
                    y = continuationHeader(c, hospital, brand, appointment);
                }
                closing(c, appointment, brand, y - 14);
                footer(c, hospital, doc.getNumberOfPages());
            } finally {
                c.close();
            }
            if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
                for (PDPage p : doc.getPages()) {
                    try (PDPageContentStream wm = new PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        watermark(wm, "CANCELADA", W, H, RED);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el comprobante en PDF", e);
        }
    }

    /** Por encima de esta altura termina el contenido; abajo va el pie de página. */
    private static final float FOOTER_TOP = 64;
    private static final float REC_SIZE = 9.5f;
    private static final float REC_LEADING = 13.5f;

    private PDPageContentStream newPage(PDDocument doc) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDPageContentStream c = new PDPageContentStream(doc, page);
        fill(c, 0, 0, W, H, new int[]{246, 247, 250});
        return c;
    }

    /** Membrete compacto de la hoja 2 en adelante. */
    private float continuationHeader(PDPageContentStream c, HospitalConfig hospital, int[] brand, Appointment a) throws IOException {
        fill(c, 0, H - 58, W, 58, INK);
        fill(c, 0, H - 58, W, 4, brand);
        text(c, BOLD, 13, M, H - 34, truncate(hospital.getName(), BOLD, 13, 300), WHITE);
        textRight(c, BOLD, 10, W - M, H - 34, "Cita #" + String.format("%06d", a.getId()) + " (continuación)", FAINT);
        return H - 70;
    }

    // ── Secciones ────────────────────────────────────────────────

    private float header(PDPageContentStream c, PDDocument doc, HospitalConfig hospital, int[] brand, Appointment a) throws IOException {
        float h = 124;
        float top = H - h;
        fill(c, 0, top, W, h, INK);
        // Halo de color de marca a la derecha, recortado a la banda; luego la franja inferior.
        c.saveGraphicsState();
        c.addRect(0, top, W, h);
        c.clip();
        circle(c, W - 40, H - 10, 120, shade(brand, 0.35));
        circle(c, W - 10, H - 70, 70, brand);
        c.restoreGraphicsState();
        fill(c, 0, top, W, 5, brand);

        float tile = 54;
        float tileY = top + (h - tile) / 2 + 2;
        roundFill(c, M, tileY, tile, tile, 14, WHITE);
        PDImageXObject logo = logoLoader.load(doc, hospital.getLogoUrl());
        if (logo != null) {
            float scale = Math.min((tile - 10) / logo.getWidth(), (tile - 10) / logo.getHeight());
            float lw = logo.getWidth() * scale;
            float lh = logo.getHeight() * scale;
            c.drawImage(logo, M + (tile - lw) / 2, tileY + (tile - lh) / 2, lw, lh);
        } else {
            textCenter(c, BOLD, 20, M + tile / 2, tileY + 19, initials(hospital.getName()), brand);
        }
        float tx = M + tile + 16;
        label(c, tx, top + h - 42, "Comprobante de cita médica", tint(brand, 0.45));
        text(c, BOLD, 20, tx, top + h - 66, truncate(hospital.getName(), BOLD, 20, 300), WHITE);
        String contact = join(" · ", hospital.getContactPhone(), hospital.getContactEmail());
        if (!contact.isEmpty()) {
            text(c, REGULAR, 9, tx, top + h - 82, truncate(contact, REGULAR, 9, 300), FAINT);
        }
        String address = hospitalAddress(hospital);
        if (!address.isEmpty()) {
            text(c, REGULAR, 8.5f, tx, top + h - 95, truncate(address, REGULAR, 8.5f, 320), FAINT);
        }

        float right = W - M;
        textRight(c, BOLD, 8, right, top + h - 38, "FOLIO", tint(brand, 0.5));
        textRight(c, BOLD, 24, right, top + h - 64, "#" + String.format("%06d", a.getId()), WHITE);
        String status = statusLabel(a.getStatus());
        int[][] colors = statusColors(a.getStatus());
        float pw = width(BOLD, 8.5f, status) + 16;
        pill(c, right - pw, top + 22, status, colors[0], colors[1], 8.5f);
        return top;
    }

    /** Boleto: fecha grande, hora, doctor y, en el talón perforado, el QR de llegada. */
    private float ticket(PDPageContentStream c, PDDocument doc, Appointment a, int[] brand, float top) throws IOException {
        float h = 172;
        float y = top - h;
        float stubW = 160;
        roundFill(c, M, y, CW, h, 16, WHITE);
        roundStroke(c, M, y, CW, h, 16, LINE, 0.8f);
        // Acento lateral con el color de marca.
        roundFill(c, M, y + 18, 5, h - 36, 2.5f, brand);

        float x = M + 26;
        label(c, x, top - 28, "Tu cita", MUTED);
        String day = String.valueOf(a.getAppointmentDate().getDayOfMonth());
        text(c, BOLD, 54, x, top - 82, day, INK);
        float dx = x + width(BOLD, 54, day) + 12;
        String weekday = a.getAppointmentDate().format(WEEKDAY);
        text(c, BOLD, 15, dx, top - 58, capitalize(weekday), INK);
        text(c, REGULAR, 11, dx, top - 74, a.getAppointmentDate().format(MONTH_YEAR), MUTED);

        float timeY = top - 112;
        roundFill(c, x, timeY - 6, 150, 26, 8, tint(brand, 0.9));
        text(c, BOLD, 13, x + 10, timeY + 2.5f, a.getStartTime().format(TIME) + "  -  " + a.getEndTime().format(TIME) + " h", shade(brand, 0.15));

        String doctor = "Dr(a). " + a.getDoctor().getUser().getFirstName() + " " + a.getDoctor().getUser().getLastName();
        float infoW = CW - stubW - 52;
        text(c, BOLD, 12.5f, x, top - 145, truncate(doctor, BOLD, 12.5f, infoW), INK);
        String spec = a.getDoctor().getSpecialty().getName()
                + (a.getDoctor().getLicenseNumber() != null && !a.getDoctor().getLicenseNumber().isBlank()
                ? "  ·  Céd. " + a.getDoctor().getLicenseNumber() : "");
        text(c, REGULAR, 9.5f, x, top - 159, truncate(spec, REGULAR, 9.5f, infoW), MUTED);

        float px = M + CW - stubW;
        perforation(c, px, y, h, new int[]{246, 247, 250}, LINE);
        PDImageXObject qr = qr(doc, tenantLinks.frontendBase() + "/cita-confirmada?token=" + a.getCancelToken(), INK);
        float qs = 104;
        if (qr != null) {
            c.drawImage(qr, px + (stubW - qs) / 2, y + 44, qs, qs);
        }
        textCenter(c, BOLD, 8.5f, px + stubW / 2, y + 30, "Muéstralo en recepción", INK_SOFT);
        textCenter(c, REGULAR, 7.5f, px + stubW / 2, y + 18, "al llegar a tu cita", MUTED);
        return y;
    }

    private float people(PDPageContentStream c, Appointment a, float top) throws IOException {
        float gap = 14;
        float cw = (CW - gap) / 2;
        Patient p = a.getPatient();
        Branch b = a.getBranch();
        List<String[]> left = new ArrayList<>();
        left.add(new String[]{"Nombre", p.getFirstName() + " " + p.getLastName()});
        if (notBlank(p.getPhone())) left.add(new String[]{"Teléfono", p.getPhone()});
        if (notBlank(p.getEmail())) left.add(new String[]{"Correo", p.getEmail()});
        List<String[]> right = new ArrayList<>();
        right.add(new String[]{"Sede", b.getName()});
        String address = join(", ", b.getAddress(), b.getNeighborhood(), b.getCity(), b.getState(),
                notBlank(b.getPostalCode()) ? "C.P. " + b.getPostalCode() : null);
        if (!address.isEmpty()) right.add(new String[]{"Dirección", address});
        if (notBlank(b.getPhone())) right.add(new String[]{"Teléfono", b.getPhone()});
        float h = 40 + Math.max(left.size(), right.size()) * 30;
        infoCard(c, M, top - h, cw, h, "Paciente", left);
        infoCard(c, M + cw + gap, top - h, cw, h, "Lugar de la cita", right);
        return top - h;
    }

    private void infoCard(PDPageContentStream c, float x, float y, float w, float h, String title, List<String[]> rows) throws IOException {
        roundFill(c, x, y, w, h, 14, WHITE);
        roundStroke(c, x, y, w, h, 14, LINE, 0.8f);
        label(c, x + 18, y + h - 24, title, MUTED);
        float ry = y + h - 46;
        for (String[] row : rows) {
            text(c, REGULAR, 7.5f, x + 18, ry + 9, row[0], FAINT);
            text(c, BOLD, 10.5f, x + 18, ry - 3, truncate(row[1], BOLD, 10.5f, w - 36), INK_SOFT);
            ry -= 30;
        }
    }

    /** Información médica (foto tomada al agendar) y motivo: lo que el doctor necesita ver. */
    private float medical(PDPageContentStream c, Appointment a, float top) throws IOException {
        String allergies = notBlank(a.getAllergiesSnapshot()) ? a.getAllergiesSnapshot() : "No especificadas";
        boolean hasAllergies = notBlank(a.getAllergiesSnapshot()) && !"Ninguna".equalsIgnoreCase(a.getAllergiesSnapshot().trim());
        String reason = a.getReasonForVisit();
        List<String> reasonLines = notBlank(reason) ? wrap(reason, REGULAR, 9.5f, CW - 36, 3) : List.of();
        float h = 92 + (reasonLines.isEmpty() ? 0 : 18 + reasonLines.size() * 13);
        float y = top - h;
        roundFill(c, M, y, CW, h, 14, WHITE);
        roundStroke(c, M, y, CW, h, 14, LINE, 0.8f);
        label(c, M + 18, top - 24, "Información médica", MUTED);

        float boxW = (CW - 36 - 12) / 2;
        float boxY = top - 80;
        roundFill(c, M + 18, boxY, boxW, 42, 10, hasAllergies ? RED_BG : PANEL);
        text(c, REGULAR, 7.5f, M + 30, boxY + 27, "ALERGIAS", hasAllergies ? RED : FAINT);
        text(c, BOLD, 11, M + 30, boxY + 11, truncate(allergies, BOLD, 11, boxW - 24), hasAllergies ? RED : INK_SOFT);
        float bx = M + 18 + boxW + 12;
        roundFill(c, bx, boxY, boxW, 42, 10, PANEL);
        text(c, REGULAR, 7.5f, bx + 12, boxY + 27, "TIPO DE SANGRE", FAINT);
        text(c, BOLD, 11, bx + 12, boxY + 11, bloodType(a.getBloodTypeSnapshot(), a.getBloodTypeOtherSnapshot()), INK_SOFT);

        if (!reasonLines.isEmpty()) {
            text(c, REGULAR, 7.5f, M + 18, boxY - 16, "MOTIVO DE LA CONSULTA", FAINT);
            lines(c, reasonLines, REGULAR, 9.5f, M + 18, boxY - 30, 13, BODY);
        }
        return y;
    }

    private float payment(PDPageContentStream c, Appointment a, int[] brand, float top) throws IOException {
        float h = 58;
        float y = top - h;
        roundFill(c, M, y, CW, h, 14, WHITE);
        roundStroke(c, M, y, CW, h, 14, LINE, 0.8f);
        label(c, M + 18, top - 24, "Consulta", MUTED);
        BigDecimal price = a.getPrice();
        String amount = price != null && price.signum() > 0 ? money(price) : "Precio por confirmar en recepción";
        text(c, BOLD, price != null && price.signum() > 0 ? 17 : 11, M + 18, y + 14, amount, INK);

        String ps = a.getPaymentStatus() != null ? a.getPaymentStatus().name() : "UNPAID";
        String[] label;
        int[][] colors;
        switch (ps) {
            case "PAID" -> { label = new String[]{"PAGADA"}; colors = new int[][]{GREEN_BG, GREEN}; }
            case "PENDING" -> { label = new String[]{"SPEI PENDIENTE"}; colors = new int[][]{BLUE_BG, BLUE}; }
            case "REFUNDED" -> { label = new String[]{"REEMBOLSADA"}; colors = new int[][]{PANEL, MUTED}; }
            default -> { label = new String[]{"PAGO EN RECEPCIÓN"}; colors = new int[][]{AMBER_BG, AMBER}; }
        }
        float pw = width(BOLD, 8.5f, label[0]) + 16;
        pill(c, M + CW - 18 - pw, y + 20, label[0], colors[0], colors[1], 8.5f);
        return y;
    }

    /** Recomendaciones de la especialidad, una por viñeta (separadas por "•" o por renglón). */
    private List<List<String>> recommendationLines(Appointment a) throws IOException {
        String raw = notBlank(a.getDoctor().getSpecialty().getRecommendations())
                ? a.getDoctor().getSpecialty().getRecommendations() : DEFAULT_RECOMMENDATIONS;
        List<List<String>> out = new ArrayList<>();
        for (String part : raw.split("[•\\n]")) {
            if (!part.isBlank()) out.add(wrap(part.trim(), REGULAR, REC_SIZE, CW - 60, 0));
        }
        return out;
    }

    private float closingHeight(Appointment a) throws IOException {
        if (a.getStatus() == AppointmentStatus.CANCELLED) {
            int lines = notBlank(a.getCancelReason()) ? wrap("Motivo: " + a.getCancelReason(), REGULAR, REC_SIZE, CW - 36, 0).size() : 0;
            return 46 + lines * REC_LEADING;
        }
        float h = 44;
        for (List<String> l : recommendationLines(a)) h += l.size() * REC_LEADING + 6;
        return h;
    }

    /** Recomendaciones (cita vigente, con viñetas y justificadas) o el detalle de la cancelación. */
    private void closing(PDPageContentStream c, Appointment a, int[] brand, float top) throws IOException {
        float h = closingHeight(a);
        if (a.getStatus() == AppointmentStatus.CANCELLED) {
            List<String> reason = notBlank(a.getCancelReason()) ? wrap("Motivo: " + a.getCancelReason(), REGULAR, REC_SIZE, CW - 36, 0) : List.of();
            roundFill(c, M, top - h, CW, h, 14, RED_BG);
            text(c, BOLD, 11, M + 18, top - 24, "Esta cita fue cancelada"
                    + (a.getCancelledAt() != null ? " el " + a.getCancelledAt().format(STAMP) : ""), RED);
            justified(c, reason, REGULAR, REC_SIZE, M + 18, top - 40, REC_LEADING, CW - 36, RED);
            return;
        }
        roundFill(c, M, top - h, CW, h, 14, tint(brand, 0.93));
        label(c, M + 18, top - 24, "Antes de tu cita", shade(brand, 0.2));
        float ly = top - 44;
        for (List<String> l : recommendationLines(a)) {
            check(c, M + 18, ly - 1, shade(brand, 0.1));
            ly = justified(c, l, REGULAR, REC_SIZE, M + 34, ly, REC_LEADING, CW - 60, BODY) - 6;
        }
    }

    private void footer(PDPageContentStream c, HospitalConfig hospital, int page) throws IOException {
        line(c, M, 46, W - M, 46, LINE, 0.8f);
        String legal = join(" · ", hospital.getLegalName(), notBlank(hospital.getRfc()) ? "RFC " + hospital.getRfc() : null,
                hospitalAddress(hospital));
        text(c, REGULAR, 7.5f, M, 32, truncate(legal.isEmpty()
                ? "Para cancelar o reprogramar usa el enlace de tu correo de confirmación o llama al hospital." : legal,
                REGULAR, 7.5f, CW - 70), MUTED);
        text(c, REGULAR, 7.5f, M, 21, "Generado el " + LocalDateTime.now().format(STAMP)
                + " · Documento informativo, no es un comprobante fiscal (CFDI).", FAINT);
        textRight(c, BOLD, 7.5f, W - M, 32, "Hoja " + page, FAINT);
        textRight(c, BOLD, 7.5f, W - M, 21, "Nexora Citas", FAINT);
    }

    /** Dirección del hospital en una línea (calle, colonia, ciudad, estado y CP). */
    static String hospitalAddress(HospitalConfig h) {
        return join(", ", h.getAddressLine(), h.getNeighborhood(), h.getCity(), h.getState(),
                notBlank(h.getPostalCode()) ? "C.P. " + h.getPostalCode() : null);
    }

    // ── Helpers ─────────────────────────────────────────────────

    static String statusLabel(AppointmentStatus s) {
        return switch (s) {
            case SCHEDULED -> "PROGRAMADA";
            case CANCELLED -> "CANCELADA";
            case COMPLETED -> "ATENDIDA";
            case NO_SHOW -> "NO ASISTIÓ";
        };
    }

    static int[][] statusColors(AppointmentStatus s) {
        return switch (s) {
            case SCHEDULED -> new int[][]{{219, 234, 254}, BLUE};
            case CANCELLED -> new int[][]{{254, 226, 226}, RED};
            case COMPLETED -> new int[][]{{209, 250, 229}, GREEN};
            case NO_SHOW -> new int[][]{{226, 232, 240}, BODY};
        };
    }

    static String bloodType(BloodType type, String other) {
        if (type == null) return "No especificado";
        return switch (type) {
            case A_POSITIVE -> "A+";
            case A_NEGATIVE -> "A-";
            case B_POSITIVE -> "B+";
            case B_NEGATIVE -> "B-";
            case AB_POSITIVE -> "AB+";
            case AB_NEGATIVE -> "AB-";
            case O_POSITIVE -> "O+";
            case O_NEGATIVE -> "O-";
            case OTHER -> notBlank(other) ? "Otro (" + other + ")" : "Otro";
        };
    }

    static String money(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(MX).format(value) + " MXN";
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (notBlank(p)) {
                if (!sb.isEmpty()) sb.append(sep);
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }
}
