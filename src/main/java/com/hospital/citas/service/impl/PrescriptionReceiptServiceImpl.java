package com.hospital.citas.service.impl;

import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.HospitalConfig;
import com.hospital.citas.entity.Prescription;
import com.hospital.citas.enums.BloodType;
import com.hospital.citas.service.HospitalConfigService;
import com.hospital.citas.service.PrescriptionReceiptService;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.time.format.DateTimeFormatter;

/**
 * Genera la receta médica en PDF, en memoria -- mismo criterio visual (banda de marca, logo
 * opcional con fallback en cascada, panel de datos) que {@link AppointmentReceiptServiceImpl},
 * pero autocontenido: es el único otro PDF del sistema hasta ahora, así que extraer un kit
 * compartido sería una abstracción prematura para dos usos (ver guía de "no antes de que haga
 * falta"). Trae, en este orden: encabezado, datos de doctor/paciente, tipo de sangre
 * (mismo dato "congelado" de la cita que ya usa el comprobante -- ver
 * Appointment#bloodTypeSnapshot), el texto libre de la receta, y una línea de firma física para
 * el doctor (la firma se maneja en papel, no digital -- ver decisión de producto).
 */
@Service
@RequiredArgsConstructor
public class PrescriptionReceiptServiceImpl implements PrescriptionReceiptService {

    private static final Logger log = LoggerFactory.getLogger(PrescriptionReceiptServiceImpl.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final float PAGE_W = PDRectangle.A4.getWidth();
    private static final float PAGE_H = PDRectangle.A4.getHeight();
    private static final float MARGIN = 45;

    private static final int[] WHITE = {255, 255, 255};
    private static final int[] INK = {31, 41, 55};
    private static final int[] MUTED = {107, 114, 128};
    private static final int[] PANEL_BG = {248, 250, 252};
    private static final int[] BORDER = {226, 232, 240};

    private final HospitalConfigService hospitalConfigService;

    @Value("${app.uploads.dir}")
    private String uploadsDir;

    /** Límite inferior del texto (por encima de la banda del pie de página). */
    private static final float CONTENT_BOTTOM = 60;

    /** Estado de dibujo de un documento que puede crecer a varias hojas: la hoja actual, su
     * flujo de contenido y el cursor vertical. Cada hoja nueva repite el encabezado y el pie
     * de marca, para que una receta larga se vea igual de formal en todas sus páginas. */
    private final class Flow {
        final PDDocument document;
        final HospitalConfig hospital;
        final int[] brand;
        final PDImageXObject logo;
        PDPageContentStream content;
        float y;

        Flow(PDDocument document, HospitalConfig hospital, int[] brand) {
            this.document = document;
            this.hospital = hospital;
            this.brand = brand;
            this.logo = loadLogo(document, hospital);
        }

        void startPage(boolean continuation) throws IOException {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = drawHeader(content, logo, hospital, brand, continuation ? "Receta médica (continuación)" : "Receta médica");
            drawFooter(content, hospital, brand);
        }

        /** Salta a una hoja nueva si lo que sigue ({@code needed} de alto) ya no cabe. */
        void ensureSpace(float needed) throws IOException {
            if (y - needed < CONTENT_BOTTOM) {
                startPage(true);
            }
        }

        void finish() throws IOException {
            if (content != null) {
                content.close();
                content = null;
            }
        }
    }

    @Override
    public byte[] build(Prescription prescription) {
        HospitalConfig hospital = hospitalConfigService.getEntity();
        int[] brand = hexToRgb(hospital.getPrimaryColor());
        Appointment appointment = prescription.getAppointment();

        try (PDDocument document = new PDDocument()) {
            Flow flow = new Flow(document, hospital, brand);
            flow.startPage(false);
            flow.y -= 20;
            flow.y = drawInfoPanel(flow.content, appointment, flow.y);
            flow.y -= 26;
            flow.y = drawBloodTypeStrip(flow.content, appointment, flow.y);
            flow.y -= 26;
            if (prescription.isVoided()) {
                drawVoidedBanner(flow, prescription);
                flow.y -= 20;
            }
            if (prescription.getDiagnosis() != null && !prescription.getDiagnosis().isBlank()) {
                drawSection(flow, "DIAGNÓSTICO", prescription.getDiagnosis());
                flow.y -= 14;
            }
            drawSection(flow, "INDICACIONES", prescription.getContent());
            drawSignature(flow, appointment);
            flow.finish();
            addPageNumbers(document);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar la receta en PDF", e);
        }
    }

    // ── Secciones ────────────────────────────────────────────────

    private float drawHeader(PDPageContentStream content, PDImageXObject logo, HospitalConfig hospital, int[] brand,
                              String subtitle) throws IOException {
        float bandHeight = 100;
        float bandY = PAGE_H - bandHeight;
        fillRect(content, 0, bandY, PAGE_W, bandHeight, brand);

        float textX = MARGIN;
        if (logo != null) {
            float logoSize = 56;
            float logoY = bandY + (bandHeight - logoSize) / 2;
            float logoWidth = logo.getHeight() > 0 ? logo.getWidth() * (logoSize / logo.getHeight()) : logoSize;
            content.drawImage(logo, MARGIN, logoY, logoWidth, logoSize);
            textX = MARGIN + logoWidth + 16;
        }

        text(content, PDType1Font.HELVETICA_BOLD, 20, textX, bandY + 62, hospital.getName(), WHITE);
        text(content, PDType1Font.HELVETICA, 11, textX, bandY + 42, subtitle, WHITE);
        return bandY - 30;
    }

    /** "Página n de N" en la banda del pie -- solo cuando la receta ocupa más de una hoja. */
    private void addPageNumbers(PDDocument document) throws IOException {
        int total = document.getNumberOfPages();
        if (total < 2) {
            return;
        }
        for (int i = 0; i < total; i++) {
            try (PDPageContentStream c = new PDPageContentStream(document, document.getPage(i),
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                String label = "Página " + (i + 1) + " de " + total;
                float w = PDType1Font.HELVETICA.getStringWidth(sanitize(label)) / 1000 * 9;
                text(c, PDType1Font.HELVETICA, 9, PAGE_W - MARGIN - w, 34 / 2f - 3, label, WHITE);
            }
        }
    }

    private static final float VALUE_FONT_SIZE = 12;

    /** Panel con los datos de doctor/paciente/fecha, mismo estilo "resumen" que el comprobante
     * de cita (etiqueta chica arriba, valor grande abajo). */
    private float drawInfoPanel(PDPageContentStream content, Appointment appointment, float y) throws IOException {
        float innerPadding = 18;
        float contentWidth = (PAGE_W - MARGIN * 2) - innerPadding * 2;
        float colGap = 24;
        float colWidth = (contentWidth - colGap) / 2;
        float rowHeight = 46;
        float panelHeight = rowHeight * 2 + 24;
        float panelTop = y;
        float panelBottom = panelTop - panelHeight;

        fillRect(content, MARGIN, panelBottom, PAGE_W - MARGIN * 2, panelHeight, PANEL_BG);
        strokeRect(content, MARGIN, panelBottom, PAGE_W - MARGIN * 2, panelHeight, BORDER);

        float innerX = MARGIN + innerPadding;
        float innerY = panelTop - 26;
        float col2X = innerX + colWidth + colGap;

        String patientLine = appointment.getPatient().getFirstName() + " " + appointment.getPatient().getLastName();
        String doctorLine = "Dr(a). " + appointment.getDoctor().getUser().getFirstName() + " "
                + appointment.getDoctor().getUser().getLastName() + " · " + appointment.getDoctor().getSpecialty().getName();

        field(content, innerX, innerY, "PACIENTE", truncateToWidth(patientLine, colWidth));
        field(content, col2X, innerY, "DOCTOR", truncateToWidth(doctorLine, colWidth));
        innerY -= rowHeight;

        String license = appointment.getDoctor().getLicenseNumber();
        field(content, innerX, innerY, "FECHA DE EMISIÓN", LocalDate.now().format(DATE_FMT));
        field(content, col2X, innerY, "CÉDULA PROFESIONAL",
                license != null && !license.isBlank() ? license : "No especificada");

        return panelBottom;
    }

    /** Solo el tipo de sangre del paciente -- las alergias NO se imprimen en la receta (decisión
     * de producto: el doctor las ve en pantalla al generarla, ver DoctorAgenda.jsx, pero no
     * deben quedar en el papel que se lleva el paciente). Se usa la foto tomada al agendar esta
     * cita (bloodTypeSnapshot), no el dato vigente del paciente, por la misma razón que el
     * comprobante: que la receta refleje lo que era cierto en esa visita. */
    private float drawBloodTypeStrip(PDPageContentStream content, Appointment appointment, float y) throws IOException {
        String bloodType = bloodTypeLabel(appointment.getBloodTypeSnapshot(), appointment.getBloodTypeOtherSnapshot());

        float bandHeight = 30;
        float bandTop = y;
        float bandBottom = bandTop - bandHeight;
        fillRect(content, MARGIN, bandBottom, PAGE_W - MARGIN * 2, bandHeight, PANEL_BG);
        strokeRect(content, MARGIN, bandBottom, PAGE_W - MARGIN * 2, bandHeight, BORDER);
        text(content, PDType1Font.HELVETICA_BOLD, 11, MARGIN + 14, bandBottom + 11,
                "TIPO DE SANGRE: " + bloodType, INK);
        return bandBottom;
    }

    /** Aviso en rojo cuando la receta fue anulada: sigue existiendo (documento médico), pero
     * quien la lea debe saber que ya no es vigente y por qué. */
    private void drawVoidedBanner(Flow flow, Prescription prescription) throws IOException {
        int[] red = {185, 28, 28};
        int[] redBg = {254, 226, 226};
        String when = prescription.getVoidedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        String reason = "Motivo: " + (prescription.getVoidReason() == null ? "" : prescription.getVoidReason())
                + " (anulada el " + when + ")";
        List<String> lines = wrapToLines(PDType1Font.HELVETICA, 10, reason, PAGE_W - MARGIN * 2 - 28);
        float height = 34 + 14 * lines.size();
        flow.ensureSpace(height + 4);
        float bottom = flow.y - height;
        fillRect(flow.content, MARGIN, bottom, PAGE_W - MARGIN * 2, height, redBg);
        strokeRect(flow.content, MARGIN, bottom, PAGE_W - MARGIN * 2, height, red);
        text(flow.content, PDType1Font.HELVETICA_BOLD, 13, MARGIN + 14, flow.y - 20, "RECETA ANULADA - NO VIGENTE", red);
        float lineY = flow.y - 36;
        for (String line : lines) {
            text(flow.content, PDType1Font.HELVETICA, 10, MARGIN + 14, lineY, line, INK);
            lineY -= 14;
        }
        flow.y = bottom;
    }

    /** Sección de texto libre de la receta (DIAGNÓSTICO e INDICACIONES) -- lo que el doctor
     * escribió (diagnóstico, indicaciones, dosis, medicamentos). Si no cabe en la hoja
     * actual, sigue en una hoja nueva con el mismo encabezado y pie. */
    private void drawSection(Flow flow, String title, String body) throws IOException {
        flow.ensureSpace(20 + 16);
        text(flow.content, PDType1Font.HELVETICA_BOLD, 12, MARGIN, flow.y, title, MUTED);
        flow.y -= 20;
        float maxWidth = PAGE_W - MARGIN * 2;
        // Cada renglón que escribió el doctor (un medicamento por línea, típicamente) se
        // respeta como su propio párrafo -- sanitize() convierte los saltos de línea en
        // espacios, así que envolver todo el texto de un jalón fundía la receta en un solo
        // párrafo corrido.
        for (String paragraph : body.split("\\R", -1)) {
            if (paragraph.isBlank()) {
                flow.y -= 10;
                continue;
            }
            List<String> lines = wrapToLines(PDType1Font.HELVETICA, 11, paragraph, maxWidth);
            for (int i = 0; i < lines.size(); i++) {
                flow.ensureSpace(16);
                boolean last = i == lines.size() - 1;
                drawLine(flow.content, PDType1Font.HELVETICA, 11, MARGIN, flow.y, lines.get(i), INK, last ? 0 : maxWidth);
                flow.y -= 16;
            }
            flow.y -= 4;
        }
    }

    /** Línea para firma de puño y letra del doctor sobre el papel impreso (no firma digital --
     * ver decisión de producto), con su nombre y cédula profesional impresos debajo -- como en
     * una receta física real. En una receta corta queda fija cerca del pie de página; si el
     * texto llega hasta ahí, se recorre para ir justo al final del contenido, y si ya no cabe
     * en la hoja, pasa a una hoja nueva. */
    private void drawSignature(Flow flow, Appointment appointment) throws IOException {
        float lineY = Math.min(150, flow.y - 40);
        if (lineY - 42 < 54) {
            flow.startPage(true);
            lineY = flow.y - 30;
        }
        PDPageContentStream content = flow.content;
        float lineWidth = 220;
        float lineX = PAGE_W - MARGIN - lineWidth;

        content.setStrokingColor(INK[0], INK[1], INK[2]);
        content.setLineWidth(1);
        content.moveTo(lineX, lineY);
        content.lineTo(lineX + lineWidth, lineY);
        content.stroke();

        String doctorName = "Dr(a). " + appointment.getDoctor().getUser().getFirstName() + " "
                + appointment.getDoctor().getUser().getLastName();
        String license = appointment.getDoctor().getLicenseNumber();
        String licenseLine = "Cédula profesional: " + (license != null && !license.isBlank() ? license : "No especificada");

        text(content, PDType1Font.HELVETICA_BOLD, 11, lineX, lineY - 16, doctorName, INK);
        text(content, PDType1Font.HELVETICA, 9, lineX, lineY - 29, licenseLine, MUTED);
        text(content, PDType1Font.HELVETICA, 8, lineX, lineY - 42, "Firma del médico", MUTED);
    }

    private void drawFooter(PDPageContentStream content, HospitalConfig hospital, int[] brand) throws IOException {
        float bandHeight = 34;
        fillRect(content, 0, 0, PAGE_W, bandHeight, brand);
        String contact = joinNonBlank(" · ", hospital.getContactPhone(), hospital.getContactEmail());
        String footerText = contact.isEmpty() ? "Documento generado por el sistema de citas" : contact;
        float textWidth = PDType1Font.HELVETICA.getStringWidth(footerText) / 1000 * 9;
        text(content, PDType1Font.HELVETICA, 9, (PAGE_W - textWidth) / 2, bandHeight / 2 - 3, footerText, WHITE);
    }

    private String bloodTypeLabel(BloodType bloodType, String bloodTypeOther) {
        if (bloodType == null) {
            return "No especificado";
        }
        return switch (bloodType) {
            case A_POSITIVE -> "A+";
            case A_NEGATIVE -> "A-";
            case B_POSITIVE -> "B+";
            case B_NEGATIVE -> "B-";
            case AB_POSITIVE -> "AB+";
            case AB_NEGATIVE -> "AB-";
            case O_POSITIVE -> "O+";
            case O_NEGATIVE -> "O-";
            case OTHER -> bloodTypeOther != null && !bloodTypeOther.isBlank()
                    ? "Otro (" + bloodTypeOther + ")" : "Otro";
        };
    }

    // ── Helpers de dibujo (ver AppointmentReceiptServiceImpl para el mismo patrón) ──────

    private void field(PDPageContentStream content, float x, float y, String label, String value) throws IOException {
        text(content, PDType1Font.HELVETICA_BOLD, 8, x, y, label, MUTED);
        text(content, PDType1Font.HELVETICA_BOLD, VALUE_FONT_SIZE, x, y - 16, value, INK);
    }

    private String truncateToWidth(String value, float maxWidth) throws IOException {
        PDFont font = PDType1Font.HELVETICA_BOLD;
        String sanitized = sanitize(value);
        if (font.getStringWidth(sanitized) / 1000 * VALUE_FONT_SIZE <= maxWidth) {
            return sanitized;
        }
        String ellipsis = "...";
        StringBuilder result = new StringBuilder();
        for (char c : sanitized.toCharArray()) {
            String candidate = result + String.valueOf(c) + ellipsis;
            if (font.getStringWidth(candidate) / 1000 * VALUE_FONT_SIZE > maxWidth) {
                break;
            }
            result.append(c);
        }
        return result + ellipsis;
    }

    private void text(PDPageContentStream content, PDFont font, float size, float x, float y, String value, int[] rgb) throws IOException {
        content.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(sanitize(value));
        content.endText();
    }

    /** Parte un párrafo en renglones que caben en {@code maxWidth} (PDFBox no hace word-wrap). */
    private List<String> wrapToLines(PDFont font, float size, String value, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : sanitize(value).split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.getStringWidth(candidate) / 1000 * size > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private void fillRect(PDPageContentStream content, float x, float y, float w, float h, int[] rgb) throws IOException {
        content.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        content.addRect(x, y, w, h);
        content.fill();
    }

    private void strokeRect(PDPageContentStream content, float x, float y, float w, float h, int[] rgb) throws IOException {
        content.setStrokingColor(rgb[0], rgb[1], rgb[2]);
        content.setLineWidth(1);
        content.addRect(x, y, w, h);
        content.stroke();
    }

    private void drawLine(PDPageContentStream content, PDFont font, float size, float x, float y,
                          String line, int[] rgb, float justifyWidth) throws IOException {
        String[] words = line.split(" ");
        if (justifyWidth <= 0 || words.length < 2) {
            text(content, font, size, x, y, line, rgb);
            return;
        }
        float wordsWidth = 0;
        for (String w : words) {
            wordsWidth += font.getStringWidth(w) / 1000 * size;
        }
        float gap = (justifyWidth - wordsWidth) / (words.length - 1);
        if (gap > font.getStringWidth(" ") / 1000 * size * 3) {
            text(content, font, size, x, y, line, rgb);
            return;
        }
        float cursor = x;
        for (String w : words) {
            text(content, font, size, cursor, y, w, rgb);
            cursor += font.getStringWidth(w) / 1000 * size + gap;
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('—', '-').replace('–', '-')
                .replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'')
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();
    }

    private String joinNonBlank(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(separator);
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    private int[] hexToRgb(String hex) {
        try {
            String clean = hex != null ? hex.replace("#", "") : "0F766E";
            if (clean.length() != 6) {
                clean = "0F766E";
            }
            return new int[]{
                    Integer.parseInt(clean.substring(0, 2), 16),
                    Integer.parseInt(clean.substring(2, 4), 16),
                    Integer.parseInt(clean.substring(4, 6), 16)
            };
        } catch (NumberFormatException e) {
            return new int[]{15, 118, 110};
        }
    }

    // Mismo criterio de caché que AppointmentReceiptServiceImpl (un PDImageXObject no se
    // puede reusar entre PDDocument distintos, pero el BufferedImage decodificado sí).
    private volatile String cachedLogoUrl;
    private volatile BufferedImage cachedLogoImage;

    private PDImageXObject loadLogo(PDDocument document, HospitalConfig hospital) {
        String logoUrl = hospital.getLogoUrl();
        if (logoUrl == null || logoUrl.isBlank()) {
            return null;
        }
        try {
            BufferedImage image = loadLogoImageCached(logoUrl);
            return image != null ? LosslessFactory.createFromImage(document, image) : null;
        } catch (IOException | RuntimeException e) {
            log.warn("No se pudo cargar el logo del hospital para la receta; se omite", e);
            return null;
        }
    }

    private BufferedImage loadLogoImageCached(String logoUrl) throws IOException {
        if (logoUrl.equals(cachedLogoUrl) && cachedLogoImage != null) {
            return cachedLogoImage;
        }
        Path logoPath = Paths.get(uploadsDir, logoUrl);
        if (!Files.exists(logoPath)) {
            return null;
        }
        BufferedImage image = ImageIO.read(logoPath.toFile());
        cachedLogoUrl = logoUrl;
        cachedLogoImage = image;
        return image;
    }
}
