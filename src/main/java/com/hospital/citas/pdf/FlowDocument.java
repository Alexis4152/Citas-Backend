package com.hospital.citas.pdf;

import com.hospital.citas.entity.HospitalConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Documento vertical que fluye solo a la hoja siguiente: encabezado del hospital, secciones,
 * pares dato/valor, párrafos y tablas, con pie "Hoja X de Y". Base del reporte de datos
 * personales (derecho de Acceso) y de la constancia de atención ARCO.
 */
public final class FlowDocument implements AutoCloseable {

    private static final float W = PDRectangle.LETTER.getWidth();
    private static final float H = PDRectangle.LETTER.getHeight();
    private static final float M = 46;
    private static final float BOTTOM = 62;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final PDDocument doc = new PDDocument();
    private final HospitalConfig hospital;
    private final int[] brand;
    private final String footerTitle;
    private PDPageContentStream c;
    private float y;

    public FlowDocument(HospitalConfig hospital, LogoLoader logoLoader, String eyebrow, String title, String subtitle) throws IOException {
        this.hospital = hospital;
        this.brand = PdfKit.hexToRgb(hospital.getPrimaryColor());
        this.footerTitle = title;
        newPage();
        PdfKit.fill(c, 0, H - 6, W, 6, brand);
        float top = H - M;
        float x = M;
        PDImageXObject logo = logoLoader.load(doc, hospital.getLogoUrl());
        if (logo != null) {
            float h = 34;
            float w = Math.min(90, logo.getWidth() * h / logo.getHeight());
            c.drawImage(logo, M, top - h + 4, w, h);
            x = M + w + 12;
        }
        PdfKit.text(c, PdfKit.BOLD, 11, x, top - 6, hospital.getName(), PdfKit.INK);
        String legal = hospital.getLegalName() != null ? hospital.getLegalName() + (hospital.getRfc() != null ? " · RFC " + hospital.getRfc() : "") : "";
        PdfKit.text(c, PdfKit.REGULAR, 8, x, top - 19, legal, PdfKit.MUTED);
        PdfKit.textRight(c, PdfKit.REGULAR, 7.5f, W - M, top - 6, "Generado el " + LocalDateTime.now().format(STAMP), PdfKit.MUTED);
        y = top - 52;
        PdfKit.label(c, M, y + 4, eyebrow, brand);
        PdfKit.text(c, PdfKit.BOLD, 18, M, y - 16, title, PdfKit.INK);
        y -= 30;
        if (subtitle != null) {
            for (String line : PdfKit.wrap(subtitle, PdfKit.REGULAR, 9, W - 2 * M, 3)) {
                PdfKit.text(c, PdfKit.REGULAR, 9, M, y - 4, line, PdfKit.BODY);
                y -= 12;
            }
        }
        y -= 10;
    }

    public int[] brand() {
        return brand;
    }

    private void newPage() throws IOException {
        if (c != null) c.close();
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        c = new PDPageContentStream(doc, page);
        y = H - M;
        if (doc.getNumberOfPages() > 1) {
            PdfKit.fill(c, 0, H - 4, W, 4, brand);
            PdfKit.text(c, PdfKit.BOLD, 9, M, y, footerTitle, PdfKit.INK);
            PdfKit.textRight(c, PdfKit.REGULAR, 7.5f, W - M, y, hospital.getName(), PdfKit.MUTED);
            y -= 22;
        }
    }

    private void ensure(float needed) throws IOException {
        if (y - needed < BOTTOM) newPage();
    }

    /** Título de sección con línea. */
    public FlowDocument section(String title) throws IOException {
        ensure(40);
        y -= 8;
        PdfKit.text(c, PdfKit.BOLD, 11.5f, M, y - 10, title, PdfKit.INK);
        y -= 16;
        PdfKit.line(c, M, y, W - M, y, PdfKit.LINE, 0.7f);
        y -= 10;
        return this;
    }

    /** Pares "dato: valor" en dos columnas; los valores largos bajan de renglón. */
    public FlowDocument fields(List<String[]> pairs) throws IOException {
        float colW = (W - 2 * M - 16) / 2;
        for (int i = 0; i < pairs.size(); i += 2) {
            float rowH = 0;
            for (int j = i; j < Math.min(i + 2, pairs.size()); j++) {
                rowH = Math.max(rowH, 12 + 11 * PdfKit.wrap(value(pairs.get(j)[1]), PdfKit.REGULAR, 9, colW, 4).size());
            }
            ensure(rowH + 4);
            for (int j = i; j < Math.min(i + 2, pairs.size()); j++) {
                float x = M + (j - i) * (colW + 16);
                PdfKit.text(c, PdfKit.REGULAR, 7, x, y - 7, pairs.get(j)[0].toUpperCase(), PdfKit.MUTED);
                float ly = y - 19;
                for (String line : PdfKit.wrap(value(pairs.get(j)[1]), PdfKit.REGULAR, 9, colW, 4)) {
                    PdfKit.text(c, PdfKit.REGULAR, 9, x, ly, line, PdfKit.INK_SOFT);
                    ly -= 11;
                }
            }
            y -= rowH + 4;
        }
        return this;
    }

    public FlowDocument paragraph(String text) throws IOException {
        return paragraph(text, PdfKit.REGULAR, PdfKit.BODY);
    }

    public FlowDocument note(String text) throws IOException {
        return paragraph(text, PdfKit.ITALIC, PdfKit.MUTED);
    }

    private FlowDocument paragraph(String text, PDFont font, int[] color) throws IOException {
        for (String line : PdfKit.wrap(text, font, 9, W - 2 * M, 0)) {
            ensure(13);
            if (!line.isEmpty()) PdfKit.text(c, font, 9, M, y - 9, line, color);
            y -= 12.5f;
        }
        y -= 4;
        return this;
    }

    /** Lista con viñetas. */
    public FlowDocument bullets(List<String> items) throws IOException {
        for (String item : items) {
            List<String> lines = PdfKit.wrap(item, PdfKit.REGULAR, 9, W - 2 * M - 14, 0);
            ensure(12.5f * lines.size() + 2);
            PdfKit.circle(c, M + 3, y - 6, 1.6f, brand);
            for (String line : lines) {
                PdfKit.text(c, PdfKit.REGULAR, 9, M + 12, y - 9, line, PdfKit.BODY);
                y -= 12.5f;
            }
            y -= 2;
        }
        y -= 2;
        return this;
    }

    /** Tabla con encabezado repetido en cada hoja. {@code weights}: ancho relativo de cada columna. */
    public FlowDocument table(String[] headers, float[] weights, List<String[]> rows, String emptyText) throws IOException {
        float total = 0;
        for (float w : weights) total += w;
        float[] widths = new float[weights.length];
        for (int i = 0; i < widths.length; i++) widths[i] = (W - 2 * M) * weights[i] / total;
        if (rows.isEmpty()) {
            ensure(18);
            PdfKit.text(c, PdfKit.ITALIC, 8.5f, M, y - 10, emptyText, PdfKit.MUTED);
            y -= 20;
            return this;
        }
        tableHeader(headers, widths);
        int index = 0;
        for (String[] row : rows) {
            int lines = 1;
            for (int i = 0; i < widths.length; i++) {
                lines = Math.max(lines, PdfKit.wrap(value(i < row.length ? row[i] : ""), PdfKit.REGULAR, 7.8f, widths[i] - 8, 4).size());
            }
            float rowH = 6 + 10 * lines;
            if (y - rowH < BOTTOM) {
                newPage();
                tableHeader(headers, widths);
            }
            if (index++ % 2 == 1) PdfKit.fill(c, M, y - rowH, W - 2 * M, rowH, PdfKit.PANEL);
            float x = M;
            for (int i = 0; i < widths.length; i++) {
                float ly = y - 11;
                for (String line : PdfKit.wrap(value(i < row.length ? row[i] : ""), PdfKit.REGULAR, 7.8f, widths[i] - 8, 4)) {
                    PdfKit.text(c, PdfKit.REGULAR, 7.8f, x + 4, ly, line, PdfKit.BODY);
                    ly -= 10;
                }
                x += widths[i];
            }
            y -= rowH;
            PdfKit.line(c, M, y, W - M, y, PdfKit.LINE, 0.4f);
        }
        y -= 10;
        return this;
    }

    private void tableHeader(String[] headers, float[] widths) throws IOException {
        ensure(40);
        PdfKit.fill(c, M, y - 16, W - 2 * M, 16, PdfKit.INK_SOFT);
        float x = M;
        for (int i = 0; i < headers.length; i++) {
            PdfKit.text(c, PdfKit.BOLD, 7.2f, x + 4, y - 11, PdfKit.truncate(headers[i], PdfKit.BOLD, 7.2f, widths[i] - 8), PdfKit.WHITE);
            x += widths[i];
        }
        y -= 16;
    }

    /** Recuadro destacado (p. ej. el resultado de la solicitud). */
    public FlowDocument callout(String title, String body, int[] bg, int[] fg) throws IOException {
        List<String> lines = PdfKit.wrap(body, PdfKit.REGULAR, 9, W - 2 * M - 24, 0);
        float h = 26 + 12.5f * lines.size();
        ensure(h + 6);
        PdfKit.roundFill(c, M, y - h, W - 2 * M, h, 6, bg);
        PdfKit.text(c, PdfKit.BOLD, 10, M + 12, y - 15, title, fg);
        float ly = y - 29;
        for (String line : lines) {
            PdfKit.text(c, PdfKit.REGULAR, 9, M + 12, ly, line, PdfKit.INK_SOFT);
            ly -= 12.5f;
        }
        y -= h + 10;
        return this;
    }

    public byte[] finish() throws IOException {
        c.close();
        c = null;
        int pages = doc.getNumberOfPages();
        String legal = hospital.getLegalName() != null ? hospital.getLegalName() : hospital.getName();
        for (int i = 0; i < pages; i++) {
            try (PDPageContentStream f = new PDPageContentStream(doc, doc.getPage(i), PDPageContentStream.AppendMode.APPEND, true)) {
                PdfKit.line(f, M, 44, W - M, 44, PdfKit.LINE, 0.6f);
                PdfKit.text(f, PdfKit.REGULAR, 7, M, 32, PdfKit.truncate(legal + " · " + footerTitle + " · Documento confidencial",
                        PdfKit.REGULAR, 7, W - 2 * M - 70), PdfKit.FAINT);
                PdfKit.textRight(f, PdfKit.REGULAR, 7, W - M, 32, "Hoja " + (i + 1) + " de " + pages, PdfKit.FAINT);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String value(String v) {
        return v == null || v.isBlank() ? "—" : v;
    }

    @Override
    public void close() throws IOException {
        if (c != null) c.close();
        doc.close();
    }
}
