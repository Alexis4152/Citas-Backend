package com.hospital.citas.pdf;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Herramientas de dibujo compartidas por los PDFs (comprobante de cita y receta): paleta,
 * rectángulos redondeados, texto con ajuste de línea, píldoras, separador "perforado" de
 * boleto, QR y marca de agua. PDFBox no hace layout: todo se posiciona a mano en puntos.
 */
public final class PdfKit {

    public static final PDFont REGULAR = PDType1Font.HELVETICA;
    public static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    public static final PDFont ITALIC = PDType1Font.HELVETICA_OBLIQUE;

    public static final int[] WHITE = {255, 255, 255};
    public static final int[] INK = {10, 15, 28};          // texto principal / banda oscura
    public static final int[] INK_SOFT = {30, 41, 59};     // slate-800
    public static final int[] BODY = {51, 65, 85};         // slate-700
    public static final int[] MUTED = {100, 116, 139};     // slate-500
    public static final int[] FAINT = {148, 163, 184};     // slate-400
    public static final int[] LINE = {226, 232, 240};      // slate-200
    public static final int[] PANEL = {248, 250, 252};     // slate-50
    public static final int[] RED_BG = {254, 242, 242};
    public static final int[] RED = {185, 28, 28};
    public static final int[] AMBER_BG = {255, 251, 235};
    public static final int[] AMBER = {180, 83, 9};
    public static final int[] GREEN_BG = {236, 253, 245};
    public static final int[] GREEN = {4, 120, 87};
    public static final int[] BLUE_BG = {239, 246, 255};
    public static final int[] BLUE = {29, 78, 216};

    private PdfKit() {
    }

    // ── Color ────────────────────────────────────────────────────

    public static int[] hexToRgb(String hex) {
        try {
            String clean = hex != null ? hex.replace("#", "") : "";
            if (clean.length() != 6) {
                return new int[]{21, 93, 234};
            }
            return new int[]{Integer.parseInt(clean.substring(0, 2), 16),
                    Integer.parseInt(clean.substring(2, 4), 16), Integer.parseInt(clean.substring(4, 6), 16)};
        } catch (NumberFormatException e) {
            return new int[]{21, 93, 234};
        }
    }

    /** Mezcla el color con blanco: {@code amount} 0 = el color, 1 = blanco (tintes suaves). */
    public static int[] tint(int[] rgb, double amount) {
        return new int[]{(int) (rgb[0] + (255 - rgb[0]) * amount), (int) (rgb[1] + (255 - rgb[1]) * amount),
                (int) (rgb[2] + (255 - rgb[2]) * amount)};
    }

    /** Oscurece el color: {@code amount} 0 = el color, 1 = negro. */
    public static int[] shade(int[] rgb, double amount) {
        return new int[]{(int) (rgb[0] * (1 - amount)), (int) (rgb[1] * (1 - amount)), (int) (rgb[2] * (1 - amount))};
    }

    // ── Formas ───────────────────────────────────────────────────

    public static void fill(PDPageContentStream c, float x, float y, float w, float h, int[] rgb) throws IOException {
        c.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        c.addRect(x, y, w, h);
        c.fill();
    }

    private static void roundPath(PDPageContentStream c, float x, float y, float w, float h, float r) throws IOException {
        float k = 0.5523f * r;
        c.moveTo(x + r, y);
        c.lineTo(x + w - r, y);
        c.curveTo(x + w - r + k, y, x + w, y + r - k, x + w, y + r);
        c.lineTo(x + w, y + h - r);
        c.curveTo(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h);
        c.lineTo(x + r, y + h);
        c.curveTo(x + r - k, y + h, x, y + h - r + k, x, y + h - r);
        c.lineTo(x, y + r);
        c.curveTo(x, y + r - k, x + r - k, y, x + r, y);
        c.closePath();
    }

    public static void roundFill(PDPageContentStream c, float x, float y, float w, float h, float r, int[] rgb) throws IOException {
        c.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        roundPath(c, x, y, w, h, r);
        c.fill();
    }

    public static void roundStroke(PDPageContentStream c, float x, float y, float w, float h, float r, int[] rgb, float width) throws IOException {
        c.setStrokingColor(rgb[0], rgb[1], rgb[2]);
        c.setLineWidth(width);
        roundPath(c, x, y, w, h, r);
        c.stroke();
    }

    public static void circle(PDPageContentStream c, float cx, float cy, float r, int[] rgb) throws IOException {
        roundFill(c, cx - r, cy - r, r * 2, r * 2, r, rgb);
    }

    public static void line(PDPageContentStream c, float x1, float y1, float x2, float y2, int[] rgb, float width) throws IOException {
        c.setStrokingColor(rgb[0], rgb[1], rgb[2]);
        c.setLineWidth(width);
        c.moveTo(x1, y1);
        c.lineTo(x2, y2);
        c.stroke();
    }

    public static void dashedLine(PDPageContentStream c, float x1, float y1, float x2, float y2, int[] rgb) throws IOException {
        c.setLineDashPattern(new float[]{4, 4}, 0);
        line(c, x1, y1, x2, y2, rgb, 1);
        c.setLineDashPattern(new float[]{}, 0);
    }

    /** Separador de "boleto": línea punteada con muescas semicirculares en ambos extremos. */
    public static void perforation(PDPageContentStream c, float x, float y, float h, int[] background, int[] lineColor) throws IOException {
        circle(c, x, y + h, 9, background);
        circle(c, x, y, 9, background);
        dashedLine(c, x, y + 12, x, y + h - 12, lineColor);
    }

    /** Píldora (chip) con texto; devuelve su ancho. */
    public static float pill(PDPageContentStream c, float x, float y, String label, int[] bg, int[] fg, float size) throws IOException {
        float w = width(BOLD, size, label) + 16;
        float h = size + 9;
        roundFill(c, x, y, w, h, h / 2, bg);
        text(c, BOLD, size, x + 8, y + 5.2f, label, fg);
        return w;
    }

    public static void check(PDPageContentStream c, float x, float y, int[] rgb) throws IOException {
        circle(c, x + 5, y + 4, 6, tint(rgb, 0.85));
        c.setStrokingColor(rgb[0], rgb[1], rgb[2]);
        c.setLineWidth(1.4f);
        c.moveTo(x + 2.2f, y + 4.2f);
        c.lineTo(x + 4.3f, y + 2.2f);
        c.lineTo(x + 8, y + 6.4f);
        c.stroke();
    }

    // ── Texto ────────────────────────────────────────────────────

    public static void text(PDPageContentStream c, PDFont font, float size, float x, float y, String value, int[] rgb) throws IOException {
        c.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        c.beginText();
        c.setFont(font, size);
        c.newLineAtOffset(x, y);
        c.showText(sanitize(value));
        c.endText();
    }

    public static void textRight(PDPageContentStream c, PDFont font, float size, float rightX, float y, String value, int[] rgb) throws IOException {
        text(c, font, size, rightX - width(font, size, value), y, value, rgb);
    }

    public static void textCenter(PDPageContentStream c, PDFont font, float size, float centerX, float y, String value, int[] rgb) throws IOException {
        text(c, font, size, centerX - width(font, size, value) / 2, y, value, rgb);
    }

    /** Texto en versalitas espaciadas (etiquetas de sección). */
    public static void label(PDPageContentStream c, float x, float y, String value, int[] rgb) throws IOException {
        c.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        c.beginText();
        c.setFont(BOLD, 7.5f);
        c.setCharacterSpacing(1.1f);
        c.newLineAtOffset(x, y);
        c.showText(sanitize(value).toUpperCase());
        c.setCharacterSpacing(0);
        c.endText();
    }

    public static float width(PDFont font, float size, String value) throws IOException {
        return font.getStringWidth(sanitize(value)) / 1000 * size;
    }

    /** Parte {@code value} en renglones que caben en {@code maxWidth}; respeta los saltos de
     * línea que haya escrito la persona. {@code maxLines} 0 = sin tope (el último se trunca con "..."). */
    public static List<String> wrap(String value, PDFont font, float size, float maxWidth, int maxLines) throws IOException {
        List<String> lines = new ArrayList<>();
        String raw = value == null ? "" : value.replace("\r", "");
        outer:
        for (String paragraph : raw.split("\n", -1)) {
            String clean = sanitize(paragraph);
            if (clean.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : clean.split(" +")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(font, size, candidate) > maxWidth && !line.isEmpty()) {
                    lines.add(line.toString());
                    if (maxLines > 0 && lines.size() >= maxLines) {
                        break outer;
                    }
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
            if (maxLines > 0 && lines.size() >= maxLines) {
                break;
            }
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if (maxLines > 0 && lines.size() == maxLines) {
            String last = lines.get(maxLines - 1);
            if (width(font, size, last) > maxWidth) {
                lines.set(maxLines - 1, truncate(last, font, size, maxWidth));
            }
        }
        return lines;
    }

    public static String truncate(String value, PDFont font, float size, float maxWidth) throws IOException {
        String s = sanitize(value);
        if (width(font, size, s) <= maxWidth) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (width(font, size, out + String.valueOf(ch) + "...") > maxWidth) {
                break;
            }
            out.append(ch);
        }
        return out + "...";
    }

    /** Dibuja renglones ya partidos; devuelve la y debajo del último. */
    public static float lines(PDPageContentStream c, List<String> lines, PDFont font, float size, float x, float y,
                              float leading, int[] rgb) throws IOException {
        for (String l : lines) {
            if (!l.isEmpty()) {
                text(c, font, size, x, y, l, rgb);
            }
            y -= leading;
        }
        return y;
    }

    /**
     * Dibuja un párrafo ya partido en renglones, justificado a {@code width}: cada renglón salvo
     * el último reparte sus espacios para llegar al margen derecho (el último queda a la izquierda,
     * como en un texto impreso). Devuelve la y debajo del último renglón.
     */
    public static float justified(PDPageContentStream c, List<String> lines, PDFont font, float size, float x, float y,
                                  float leading, float width, int[] rgb) throws IOException {
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            String[] words = l.split(" ");
            boolean last = i == lines.size() - 1;
            if (last || words.length < 2) {
                text(c, font, size, x, y, l, rgb);
            } else {
                float wordsWidth = 0;
                for (String w : words) wordsWidth += width(font, size, w);
                float gap = (width - wordsWidth) / (words.length - 1);
                if (gap > width(font, size, " ") * 4) {
                    text(c, font, size, x, y, l, rgb);
                } else {
                    float cursor = x;
                    for (String w : words) {
                        text(c, font, size, cursor, y, w, rgb);
                        cursor += width(font, size, w) + gap;
                    }
                }
            }
            y -= leading;
        }
        return y;
    }

    /** Helvetica (Type 1) solo trae WinAnsi: se cambian caracteres que no tiene por equivalentes. */
    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String s = value.replace('—', '-').replace('–', '-').replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'').replace('•', '-').replace('…', '.')
                .replaceAll("[\\r\\n\\t]+", " ");
        StringBuilder out = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            // Fuera de Latin-1 (emojis, otros alfabetos) no hay glifo: se omite en vez de tronar.
            out.append(ch < 256 ? ch : '?');
        }
        return out.toString().trim();
    }

    // ── Imágenes ─────────────────────────────────────────────────

    public static PDImageXObject qr(PDDocument doc, String content, int[] rgb) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 360, 360);
            int on = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2] | 0xFF000000;
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix,
                    new com.google.zxing.client.j2se.MatrixToImageConfig(on, 0xFFFFFFFF));
            return LosslessFactory.createFromImage(doc, image);
        } catch (Exception e) {
            return null;
        }
    }

    public static void watermark(PDPageContentStream c, String value, float pageW, float pageH, int[] rgb) throws IOException {
        float size = 92;
        float w = width(BOLD, size, value);
        double angle = Math.toRadians(32);
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setNonStrokingAlphaConstant(0.10f);
        c.setGraphicsStateParameters(gs);
        float sx = (float) (pageW / 2 - (w / 2) * Math.cos(angle));
        float sy = (float) (pageH / 2 - (w / 2) * Math.sin(angle));
        c.beginText();
        c.setFont(BOLD, size);
        c.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        c.setTextMatrix(Matrix.getRotateInstance(angle, sx, sy));
        c.showText(value);
        c.endText();
        PDExtendedGraphicsState reset = new PDExtendedGraphicsState();
        reset.setNonStrokingAlphaConstant(1f);
        c.setGraphicsStateParameters(reset);
    }

    /** Iniciales del hospital para cuando no hay logo. */
    public static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "H";
        }
        StringBuilder sb = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (w.length() > 2 && sb.length() < 2) {
                sb.append(Character.toUpperCase(w.charAt(0)));
            }
        }
        return sb.isEmpty() ? name.substring(0, 1).toUpperCase() : sb.toString();
    }
}
