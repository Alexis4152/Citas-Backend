package com.hospital.citas.service.impl;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.HospitalConfig;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.BloodType;
import com.hospital.citas.service.AppointmentReceiptService;
import com.hospital.citas.service.HospitalConfigService;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Genera el comprobante de cita en memoria (patrón 09: {@code byte[]}, sin tocar disco para el
 * PDF en sí) con un QR embebido (patrón 11) que codifica la URL pública de confirmación --
 * pensado para que recepción lo escanee al llegar el paciente y ver de inmediato el detalle
 * de la cita, sin buscarla a mano. El color de marca del hospital ({@code primaryColor})
 * ambienta el encabezado/pie, igual que en la web. El logo es un recurso OPCIONAL con
 * fallback en cascada: si no existe o falla al leerse, el comprobante se genera igual sin
 * logo (solo se registra un warning).
 */
@Service
@RequiredArgsConstructor
public class AppointmentReceiptServiceImpl implements AppointmentReceiptService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReceiptServiceImpl.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final float PAGE_W = PDRectangle.A4.getWidth();
    private static final float PAGE_H = PDRectangle.A4.getHeight();
    private static final float MARGIN = 45;

    private static final int[] WHITE = {255, 255, 255};
    private static final int[] INK = {31, 41, 55};       // gray-800, texto principal
    private static final int[] MUTED = {107, 114, 128};  // gray-500, texto secundario
    private static final int[] PANEL_BG = {248, 250, 252}; // slate-50, fondo de tarjeta
    private static final int[] BORDER = {226, 232, 240};   // slate-200

    private static final Map<AppointmentStatus, String> STATUS_LABELS = Map.of(
            AppointmentStatus.SCHEDULED, "PROGRAMADA",
            AppointmentStatus.CANCELLED, "CANCELADA",
            AppointmentStatus.COMPLETED, "ATENDIDA",
            AppointmentStatus.NO_SHOW, "NO ASISTIÓ");

    private static final Map<AppointmentStatus, int[]> STATUS_COLORS = Map.of(
            AppointmentStatus.SCHEDULED, new int[]{219, 234, 254},  // blue-100
            AppointmentStatus.CANCELLED, new int[]{254, 226, 226},  // red-100
            AppointmentStatus.COMPLETED, new int[]{220, 252, 231},  // green-100
            AppointmentStatus.NO_SHOW, new int[]{243, 244, 246});   // gray-100

    private static final Map<AppointmentStatus, int[]> STATUS_TEXT_COLORS = Map.of(
            AppointmentStatus.SCHEDULED, new int[]{30, 64, 175},
            AppointmentStatus.CANCELLED, new int[]{185, 28, 28},
            AppointmentStatus.COMPLETED, new int[]{21, 128, 61},
            AppointmentStatus.NO_SHOW, new int[]{75, 85, 99});

    private final HospitalConfigService hospitalConfigService;

    @Value("${app.uploads.dir}")
    private String uploadsDir;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    public byte[] build(Appointment appointment) {
        HospitalConfig hospital = hospitalConfigService.getEntity();
        int[] brand = hexToRgb(hospital.getPrimaryColor());

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = drawHeader(content, document, hospital, brand, appointment);
                y = drawStatusAndFolio(content, appointment, y);
                y -= 20;
                y = drawInfoPanel(content, appointment, y);
                y -= 30;
                drawQrSection(content, document, appointment, y);
                drawNoticeOrCancellation(content, appointment, y);
                drawFooter(content, hospital, brand);
                if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
                    drawCancelledWatermark(content);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el comprobante en PDF", e);
        }
    }

    // ── Secciones ────────────────────────────────────────────────

    /** Banda superior de color de marca con el logo (si existe) y el nombre del hospital. */
    private float drawHeader(PDPageContentStream content, PDDocument document, HospitalConfig hospital,
                              int[] brand, Appointment appointment) throws IOException {
        float bandHeight = 100;
        float bandY = PAGE_H - bandHeight;
        fillRect(content, 0, bandY, PAGE_W, bandHeight, brand);

        float textX = MARGIN;
        PDImageXObject logo = loadLogo(document, hospital);
        if (logo != null) {
            float logoSize = 56;
            float logoY = bandY + (bandHeight - logoSize) / 2;
            float logoWidth = logo.getHeight() > 0 ? logo.getWidth() * (logoSize / logo.getHeight()) : logoSize;
            content.drawImage(logo, MARGIN, logoY, logoWidth, logoSize);
            textX = MARGIN + logoWidth + 16;
        }

        text(content, PDType1Font.HELVETICA_BOLD, 20, textX, bandY + 62, hospital.getName(), WHITE);
        text(content, PDType1Font.HELVETICA, 11, textX, bandY + 42, "Comprobante de cita médica", WHITE);
        return bandY - 30;
    }

    /** Chip de estado (color según SCHEDULED/CANCELLED/...) a la izquierda y el folio grande
     * a la derecha, en la misma línea -- estilo "boleto". */
    private float drawStatusAndFolio(PDPageContentStream content, Appointment appointment, float y) throws IOException {
        AppointmentStatus status = appointment.getStatus();
        String label = STATUS_LABELS.getOrDefault(status, status.name());
        int[] chipBg = STATUS_COLORS.getOrDefault(status, new int[]{243, 244, 246});
        int[] chipFg = STATUS_TEXT_COLORS.getOrDefault(status, INK);

        float chipPaddingX = 10;
        float chipHeight = 22;
        float labelWidth = PDType1Font.HELVETICA_BOLD.getStringWidth(label) / 1000 * 10;
        float chipWidth = labelWidth + chipPaddingX * 2;
        fillRect(content, MARGIN, y - chipHeight + 6, chipWidth, chipHeight, chipBg);
        text(content, PDType1Font.HELVETICA_BOLD, 10, MARGIN + chipPaddingX, y - 9, label, chipFg);

        String folio = "Folio #" + appointment.getId();
        float folioWidth = PDType1Font.HELVETICA_BOLD.getStringWidth(folio) / 1000 * 16;
        text(content, PDType1Font.HELVETICA_BOLD, 16, PAGE_W - MARGIN - folioWidth, y - 2, folio, INK);

        return y - chipHeight;
    }

    private static final float VALUE_FONT_SIZE = 12;
    private static final float VALUE_LINE_HEIGHT = 15;

    /** Tarjeta con los datos de la cita en dos columnas (etiqueta chica arriba, valor grande
     * abajo), estilo "resumen de pedido" en vez de una lista plana de "Label: valor". Un
     * valor que no cabe en su ancho disponible (nombre de doctor + especialidad largos,
     * dirección larga, motivo de la cita) baja a una segunda línea dentro de su misma celda
     * en vez de truncarse -- la fila crece solo lo necesario y empuja hacia abajo las
     * filas siguientes, en vez de cortar información. */
    private float drawInfoPanel(PDPageContentStream content, Appointment appointment, float y) throws IOException {
        float innerPadding = 18;
        float contentWidth = (PAGE_W - MARGIN * 2) - innerPadding * 2;
        float colGap = 24;
        float colWidth = (contentWidth - colGap) / 2;

        String doctorLine = "Dr(a). " + appointment.getDoctor().getUser().getFirstName() + " "
                + appointment.getDoctor().getUser().getLastName() + " · " + appointment.getDoctor().getSpecialty().getName();
        String branchLine = appointment.getBranch().getName();
        String addressLine = appointment.getBranch().getAddress() != null ? appointment.getBranch().getAddress() : "";
        String patientLine = appointment.getPatient().getFirstName() + " " + appointment.getPatient().getLastName();
        String reason = appointment.getReasonForVisit();
        boolean hasReason = reason != null && !reason.isBlank();
        // Se usa la foto tomada al agendar (allergiesSnapshot/bloodTypeSnapshot), no el dato
        // vigente del paciente -- que puede haber cambiado en una cita posterior -- para que
        // el comprobante de ESTA cita siempre refleje lo que era cierto cuando ocurrió.
        String allergiesLine = appointment.getAllergiesSnapshot() != null && !appointment.getAllergiesSnapshot().isBlank()
                ? appointment.getAllergiesSnapshot() : "No especificadas";
        String bloodTypeLine = bloodTypeLabel(appointment.getBloodTypeSnapshot(), appointment.getBloodTypeOtherSnapshot());

        List<String> patientLines = wrapLines(patientLine, colWidth, 2);
        List<String> doctorLines = wrapLines(doctorLine, colWidth, 2);
        List<String> branchLines = wrapLines(branchLine, colWidth, 2);
        List<String> addressLines = wrapLines(addressLine.isEmpty() ? "—" : addressLine, colWidth, 2);
        List<String> dateLines = wrapLines(appointment.getAppointmentDate().format(DATE_FMT), colWidth, 1);
        List<String> timeLines = wrapLines(
                appointment.getStartTime().format(TIME_FMT) + " – " + appointment.getEndTime().format(TIME_FMT), colWidth, 1);
        List<String> allergiesLines = wrapLines(allergiesLine, colWidth, 2);
        List<String> bloodTypeLines = wrapLines(bloodTypeLine, colWidth, 1);
        List<String> reasonLines = hasReason ? wrapLines(reason, contentWidth, 3) : List.of();

        float row1Height = rowHeightFor(Math.max(patientLines.size(), doctorLines.size()));
        float row2Height = rowHeightFor(Math.max(branchLines.size(), addressLines.size()));
        float row3Height = rowHeightFor(Math.max(dateLines.size(), timeLines.size()));
        float row4Height = rowHeightFor(Math.max(allergiesLines.size(), bloodTypeLines.size()));
        float row5Height = hasReason ? rowHeightFor(reasonLines.size()) : 0;
        float panelHeight = row1Height + row2Height + row3Height + row4Height + row5Height + 24;
        float panelTop = y;
        float panelBottom = panelTop - panelHeight;

        fillRect(content, MARGIN, panelBottom, PAGE_W - MARGIN * 2, panelHeight, PANEL_BG);
        strokeRect(content, MARGIN, panelBottom, PAGE_W - MARGIN * 2, panelHeight, BORDER);

        float innerX = MARGIN + innerPadding;
        float innerY = panelTop - 26;
        float col2X = innerX + colWidth + colGap;

        fieldLines(content, innerX, innerY, "PACIENTE", patientLines);
        fieldLines(content, col2X, innerY, "DOCTOR", doctorLines);
        innerY -= row1Height;

        fieldLines(content, innerX, innerY, "SEDE", branchLines);
        fieldLines(content, col2X, innerY, "DIRECCIÓN", addressLines);
        innerY -= row2Height;

        fieldLines(content, innerX, innerY, "FECHA", dateLines);
        fieldLines(content, col2X, innerY, "HORA", timeLines);
        innerY -= row3Height;

        // Alergias/tipo de sangre: siempre visible (no condicionado, a diferencia del motivo)
        // -- es información que el doctor necesita para poder recetar con seguridad.
        fieldLines(content, innerX, innerY, "ALERGIAS", allergiesLines);
        fieldLines(content, col2X, innerY, "TIPO DE SANGRE", bloodTypeLines);

        if (hasReason) {
            innerY -= row4Height;
            fieldLinesJustified(content, innerX, innerY, "MOTIVO DE LA CITA", reasonLines, contentWidth);
        }

        return panelBottom;
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

    /** Alto que ocupa una fila con {@code lines} renglones de valor -- para 1 renglón da
     * exactamente 46 (el alto fijo que ya tenía cada fila antes de soportar wrap). */
    private float rowHeightFor(int lines) {
        return 16 + Math.max(lines, 1) * VALUE_LINE_HEIGHT + 15;
    }

    private static final float QR_BOX_SIZE = 134;
    private static final float QR_COLUMN_GAP = 24;

    /** Recomendaciones de llegada para una cita vigente, o el detalle de la cancelación
     * (quién y por qué) cuando la cita ya está cancelada -- no tiene sentido pedirle a
     * alguien "llegar 15 min antes" a una cita que no va a suceder. Comparte la misma
     * franja horizontal que el QR (columna izquierda) en vez de apilarse arriba de él,
     * para no dejar un bloque de espacio en blanco entre ambos. */
    private void drawNoticeOrCancellation(PDPageContentStream content, Appointment appointment, float y) throws IOException {
        float textTop = y - 14;
        float maxWidth = PAGE_W - MARGIN * 2 - QR_BOX_SIZE - QR_COLUMN_GAP;

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            text(content, PDType1Font.HELVETICA_BOLD, 11, MARGIN, textTop, "Esta cita fue cancelada", STATUS_TEXT_COLORS.get(AppointmentStatus.CANCELLED));
            textTop -= 18;
            if (appointment.getCancelReason() != null && !appointment.getCancelReason().isBlank()) {
                wrappedText(content, PDType1Font.HELVETICA, 10, MARGIN, textTop, "Motivo: " + appointment.getCancelReason(), MUTED, maxWidth, true);
            }
            return;
        }

        drawRecommendations(content, appointment, textTop, maxWidth);
    }

    private static final float BULLET_INDENT = 11;

    /** El ADMIN captura las recomendaciones de una especialidad como texto libre con viñetas
     * "•" (ver AdminSpecialties.jsx) -- esto las separa en puntos reales, cada uno con su
     * propia viñeta y ajuste de línea, en vez de un párrafo corrido donde no se distinguen
     * los puntos entre sí (mismo criterio que RecommendationsNotice.jsx en el frontend). */
    private void drawRecommendations(PDPageContentStream content, Appointment appointment, float textTop, float maxWidth) throws IOException {
        text(content, PDType1Font.HELVETICA_BOLD, 11, MARGIN, textTop, "Recomendaciones", INK);
        textTop -= 16;
        List<String> points = splitBulletPoints(recommendationsFor(appointment));
        for (String point : points) {
            if (points.size() > 1) {
                text(content, PDType1Font.HELVETICA_BOLD, 10, MARGIN, textTop, "-", MUTED);
                textTop = wrappedText(content, PDType1Font.HELVETICA, 10, MARGIN + BULLET_INDENT, textTop, point, MUTED, maxWidth - BULLET_INDENT, true);
            } else {
                textTop = wrappedText(content, PDType1Font.HELVETICA, 10, MARGIN, textTop, point, MUTED, maxWidth, true);
            }
            textTop -= 3;
        }
    }

    private List<String> splitBulletPoints(String text) {
        List<String> points = new ArrayList<>();
        for (String part : text.split("•")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                points.add(trimmed);
            }
        }
        if (points.isEmpty()) {
            points.add(text.trim());
        }
        return points;
    }

    /** Respaldo cuando la especialidad del doctor no tiene recomendaciones propias
     * capturadas (ver Specialty#recommendations, editable desde /admin/especialidades) --
     * mismo criterio que EmailServiceImpl.DEFAULT_RECOMMENDATIONS. */
    private static final String DEFAULT_RECOMMENDATIONS =
            "Llega 15 minutos antes de tu cita y trae una identificación oficial. Si no vas a poder "
            + "asistir, cancela con al menos 24 horas de anticipación para liberar el espacio.";

    private String recommendationsFor(Appointment appointment) {
        String custom = appointment.getDoctor().getSpecialty().getRecommendations();
        return (custom != null && !custom.isBlank()) ? custom.trim() : DEFAULT_RECOMMENDATIONS;
    }

    /** QR de check-in: recepción lo escanea al llegar el paciente para ver el detalle de la
     * cita sin buscarla a mano (codifica la URL pública de confirmación). Va en la columna
     * derecha, a la misma altura que el aviso/motivo de cancelación. */
    private void drawQrSection(PDPageContentStream content, PDDocument document, Appointment appointment, float y) throws IOException {
        PDImageXObject qr = buildQrCode(document, appointment);
        if (qr == null) {
            return;
        }
        float qrImageSize = 106;
        float boxPadding = 14;
        float boxX = PAGE_W - MARGIN - QR_BOX_SIZE;
        float boxHeight = qrImageSize + boxPadding * 2 + 16;
        // El borde superior de la caja queda a la altura de la parte alta del título
        // "Recomendaciones" (baseline en y-14, mayúsculas de ~8pt), para que ambos columnas
        // arranquen niveladas en vez de que la caja quede más arriba que el texto.
        float boxY = (y - 6) - boxHeight;

        strokeRect(content, boxX, boxY, QR_BOX_SIZE, boxHeight, BORDER);
        content.drawImage(qr, boxX + boxPadding, boxY + boxPadding + 16, qrImageSize, qrImageSize);
        text(content, PDType1Font.HELVETICA, 8, boxX + boxPadding, boxY + boxPadding + 4,
                "Escanéalo para ver tu cita", MUTED);
    }

    /** Banda inferior, mismo color de marca que el encabezado, para cerrar visualmente el
     * comprobante como un boleto (además de dar un lugar fijo para contacto del hospital). */
    private void drawFooter(PDPageContentStream content, HospitalConfig hospital, int[] brand) throws IOException {
        float bandHeight = 34;
        fillRect(content, 0, 0, PAGE_W, bandHeight, brand);
        String contact = joinNonBlank(" · ", hospital.getContactPhone(), hospital.getContactEmail());
        String footerText = contact.isEmpty() ? "Gracias por tu preferencia" : contact;
        float textWidth = PDType1Font.HELVETICA.getStringWidth(footerText) / 1000 * 9;
        text(content, PDType1Font.HELVETICA, 9, (PAGE_W - textWidth) / 2, bandHeight / 2 - 3, footerText, WHITE);
    }

    /** Marca de agua diagonal "CANCELADO" sobre todo el comprobante -- para que quede
     * claro a simple vista incluso si alguien vuelve a descargar/imprimir el PDF de una
     * cita ya cancelada (patrón: el comprobante de la MISMA cita se re-genera con el
     * estado vigente, así que no hace falta versionarlo). Semi-transparente para no tapar
     * el resto del contenido. */
    private void drawCancelledWatermark(PDPageContentStream content) throws IOException {
        String watermark = "CANCELADO";
        float fontSize = 90;
        PDFont font = PDType1Font.HELVETICA_BOLD;
        float textWidth = font.getStringWidth(watermark) / 1000 * fontSize;
        double angle = Math.toRadians(35);

        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setNonStrokingAlphaConstant(0.16f);
        content.setGraphicsStateParameters(gs);

        float centerX = PAGE_W / 2f;
        float centerY = PAGE_H / 2f;
        float startX = (float) (centerX - (textWidth / 2) * Math.cos(angle));
        float startY = (float) (centerY - (textWidth / 2) * Math.sin(angle));

        content.beginText();
        content.setFont(font, fontSize);
        content.setNonStrokingColor(220, 38, 38);
        content.setTextMatrix(Matrix.getRotateInstance(angle, startX, startY));
        content.showText(watermark);
        content.endText();

        PDExtendedGraphicsState resetGs = new PDExtendedGraphicsState();
        resetGs.setNonStrokingAlphaConstant(1f);
        content.setGraphicsStateParameters(resetGs);
    }

    // ── Helpers de dibujo ────────────────────────────────────────

    /** Etiqueta chica + valor grande, ya envuelto en 1+ renglones (ver {@link #wrapLines}). */
    private void fieldLines(PDPageContentStream content, float x, float y, String label, List<String> lines) throws IOException {
        text(content, PDType1Font.HELVETICA_BOLD, 8, x, y, label, MUTED);
        float lineY = y - 16;
        for (String line : lines) {
            text(content, PDType1Font.HELVETICA_BOLD, VALUE_FONT_SIZE, x, lineY, line, INK);
            lineY -= VALUE_LINE_HEIGHT;
        }
    }

    /** Igual que {@link #fieldLines} pero justificado a {@code width}: para valores que son un
     * párrafo (motivo de la cita), no un dato corto. El último renglón queda a la izquierda. */
    private void fieldLinesJustified(PDPageContentStream content, float x, float y, String label,
                                     List<String> lines, float width) throws IOException {
        text(content, PDType1Font.HELVETICA_BOLD, 8, x, y, label, MUTED);
        float lineY = y - 16;
        for (int i = 0; i < lines.size(); i++) {
            boolean last = i == lines.size() - 1;
            drawLine(content, PDType1Font.HELVETICA_BOLD, VALUE_FONT_SIZE, x, lineY, lines.get(i), INK, last ? 0 : width);
            lineY -= VALUE_LINE_HEIGHT;
        }
    }

    /** Envuelve {@code value} a lo ancho de {@code maxWidth} en como máximo {@code maxLines}
     * renglones -- a diferencia de {@link #wrappedText} (que dibuja todo lo que haga falta),
     * este devuelve la lista ya acotada porque el layout de {@link #drawInfoPanel} necesita
     * saber CUÁNTOS renglones ocupará un valor ANTES de dibujar el panel (para calcular su
     * alto). Si el texto no cabe ni en {@code maxLines} renglones, el último se trunca con
     * elipsis en vez de seguir creciendo indefinidamente (ej. un motivo de cita kilométrico). */
    private List<String> wrapLines(String value, float maxWidth, int maxLines) throws IOException {
        PDFont font = PDType1Font.HELVETICA_BOLD;
        String sanitized = sanitize(value);
        String[] words = sanitized.isEmpty() ? new String[0] : sanitized.split(" ");
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int wordIndex = 0;
        while (wordIndex < words.length && lines.size() < maxLines - 1) {
            String word = words[wordIndex];
            String candidate = line.isEmpty() ? word : line + " " + word;
            float width = font.getStringWidth(candidate) / 1000 * VALUE_FONT_SIZE;
            if (width > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder();
            } else {
                line = new StringBuilder(candidate);
                wordIndex++;
            }
        }
        StringBuilder remainder = new StringBuilder(line);
        for (int i = wordIndex; i < words.length; i++) {
            if (!remainder.isEmpty()) {
                remainder.append(" ");
            }
            remainder.append(words[i]);
        }
        String lastLine = remainder.toString();
        if (font.getStringWidth(lastLine) / 1000 * VALUE_FONT_SIZE > maxWidth) {
            lastLine = truncateToWidth(font, VALUE_FONT_SIZE, lastLine, maxWidth);
        }
        lines.add(lastLine);
        return lines;
    }

    private String truncateToWidth(PDFont font, float size, String value, float maxWidth) throws IOException {
        String sanitized = sanitize(value);
        if (font.getStringWidth(sanitized) / 1000 * size <= maxWidth) {
            return sanitized;
        }
        String ellipsis = "...";
        StringBuilder result = new StringBuilder();
        for (char c : sanitized.toCharArray()) {
            String candidate = result + String.valueOf(c) + ellipsis;
            if (font.getStringWidth(candidate) / 1000 * size > maxWidth) {
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

    /** Envuelve el texto a mano (PDFBox no hace word-wrap) para que una dirección o motivo
     * de cancelación largo no se salga de la página. */
    private float wrappedText(PDPageContentStream content, PDFont font, float size, float x, float y,
                               String value, int[] rgb, float maxWidth) throws IOException {
        return wrappedText(content, font, size, x, y, value, rgb, maxWidth, false);
    }

    /** Con {@code justify}, cada renglón salvo el último del párrafo se reparte a todo el
     * ancho estirando los espacios entre palabras (se dibuja palabra por palabra porque
     * PDFBox no justifica solo). Un renglón donde estirar los espacios se vería exagerado
     * (muy pocas palabras) se deja alineado a la izquierda. */
    private float wrappedText(PDPageContentStream content, PDFont font, float size, float x, float y,
                               String value, int[] rgb, float maxWidth, boolean justify) throws IOException {
        String sanitized = sanitize(value);
        StringBuilder line = new StringBuilder();
        float lineHeight = size + 4;
        for (String word : sanitized.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            float width = font.getStringWidth(candidate) / 1000 * size;
            if (width > maxWidth && !line.isEmpty()) {
                drawLine(content, font, size, x, y, line.toString(), rgb, justify ? maxWidth : 0);
                y -= lineHeight;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            text(content, font, size, x, y, line.toString(), rgb);
            y -= lineHeight;
        }
        return y;
    }

    /** {@code justifyWidth} > 0 reparte las palabras de la línea a ese ancho; 0 = normal. */
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
        float normalGap = font.getStringWidth(" ") / 1000 * size;
        if (gap > normalGap * 3) {
            text(content, font, size, x, y, line, rgb);
            return;
        }
        float cursor = x;
        for (String w : words) {
            text(content, font, size, cursor, y, w, rgb);
            cursor += font.getStringWidth(w) / 1000 * size + gap;
        }
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

    /** PDFBox/Helvetica solo soporta WinAnsiEncoding: cambia guiones largos y comillas
     * "inteligentes" que a veces vienen en texto capturado por el usuario, por su
     * equivalente simple, para no tronar la generación del PDF. */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('—', '-').replace('–', '-')
                .replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'')
                // Saltos de línea/tabs reales (ej. el ADMIN los tecleó en el textarea de
                // recomendaciones) tronaban PDType1Font.getStringWidth: WinAnsiEncoding no
                // tiene glifo para U+000A/U+0009. El wrap a varias líneas ya lo resuelve
                // wrappedText/wrapLines por ancho, así que un salto de línea manual solo debe
                // contar como un espacio entre palabras, nunca llegar crudo a la fuente.
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

    // Un PDImageXObject está atado al PDDocument en el que se creó (no se puede reusar entre
    // documentos distintos), pero el BufferedImage decodificado sí -- cachearlo evita releer
    // y re-decodificar el archivo del logo de disco en CADA comprobante generado (que antes
    // pasaba en cada descarga de PDF y en cada correo con comprobante adjunto). Se invalida
    // solo cuando cambia el logoUrl (ej. el ADMIN sube uno nuevo).
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
            // RuntimeException cubre, entre otros, IllegalArgumentException de PDFBox
            // cuando el formato del logo no es soportado (ej. WEBP) -- un logo con un
            // formato raro nunca debe tumbar la generación de TODO el comprobante.
            log.warn("No se pudo cargar el logo del hospital para el comprobante; se omite", e);
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

    private PDImageXObject buildQrCode(PDDocument document, Appointment appointment) {
        try {
            String content = frontendUrl + "/cita-confirmada?token=" + appointment.getCancelToken();
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            return LosslessFactory.createFromImage(document, image);
        } catch (WriterException | IOException e) {
            log.warn("No se pudo generar el QR del comprobante de la cita {}; se omite", appointment.getId(), e);
            return null;
        }
    }
}
