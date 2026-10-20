package com.hospital.citas.report;

import com.hospital.citas.entity.HospitalConfig;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.pdf.LogoLoader;
import com.hospital.citas.pdf.PdfKit;
import com.hospital.citas.report.ReportTable.Column;
import com.hospital.citas.report.ReportTable.Type;
import com.hospital.citas.service.HospitalConfigService;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Convierte un {@link ReportTable} en CSV, Excel o PDF (el mismo contenido en los tres). */
@Component
@RequiredArgsConstructor
public class ReportFileWriter {

    public enum Format {
        CSV("csv", "text/csv; charset=UTF-8"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        PDF("pdf", "application/pdf");

        public final String extension;
        public final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public static Format parse(String value) {
            try {
                return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BusinessException("Formato no válido: usa csv, xlsx o pdf");
            }
        }
    }

    private static final Locale MX = Locale.forLanguageTag("es-MX");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final HospitalConfigService hospitalConfigService;
    private final LogoLoader logoLoader;

    public byte[] write(ReportTable table, Format format, String generatedBy) {
        try {
            return switch (format) {
                case CSV -> csv(table);
                case XLSX -> xlsx(table, generatedBy);
                case PDF -> pdf(table, generatedBy);
            };
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el reporte", e);
        }
    }

    /** Nombre de archivo sin acentos ni espacios: "citas-del-periodo-2026-10-01-a-2026-10-20.pdf". */
    public static String fileName(String base, Format format) {
        String slug = java.text.Normalizer.normalize(base, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug + "." + format.extension;
    }

    // ── Texto (pantalla, PDF y CSV comparten este formato) ───────

    public static String format(Object value, Type type) {
        if (value == null) return "";
        return switch (type) {
            case MONEY -> money(value);
            case PERCENT -> String.format(MX, "%.1f%%", ((Number) value).doubleValue());
            case INT -> NumberFormat.getIntegerInstance(MX).format(((Number) value).longValue());
            case DATE -> value instanceof LocalDate d ? d.format(DATE) : value.toString();
            case TIME -> value instanceof LocalTime t ? t.format(TIME) : value.toString();
            case DATETIME -> value instanceof LocalDateTime t ? t.format(DATETIME) : value.toString();
            case TEXT -> value.toString();
        };
    }

    private static String money(Object value) {
        BigDecimal amount = value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
        NumberFormat f = NumberFormat.getCurrencyInstance(MX);
        return f.format(amount);
    }

    // ── CSV ──────────────────────────────────────────────────────

    /** UTF-8 con BOM (Excel lo abre con acentos bien); números sin símbolo para poder sumarlos. */
    private byte[] csv(ReportTable t) {
        StringBuilder sb = new StringBuilder("﻿");
        List<String> header = new ArrayList<>();
        t.columns().forEach(c -> header.add(c.label()));
        csvLine(sb, header);
        for (List<Object> row : t.rows()) csvLine(sb, csvValues(t.columns(), row));
        if (t.totals() != null) csvLine(sb, csvValues(t.columns(), t.totals()));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> csvValues(List<Column> cols, List<Object> row) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            Object v = i < row.size() ? row.get(i) : null;
            Type type = cols.get(i).type();
            if (v instanceof Number n && (type == Type.MONEY || type == Type.PERCENT)) {
                out.add(new BigDecimal(n.toString()).setScale(type == Type.MONEY ? 2 : 1, java.math.RoundingMode.HALF_UP).toPlainString());
            } else if (v instanceof Number n && type == Type.INT) {
                out.add(String.valueOf(n.longValue()));
            } else {
                out.add(format(v, type));
            }
        }
        return out;
    }

    private static void csvLine(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            String v = values.get(i) == null ? "" : values.get(i);
            // Evita que Excel interprete "=..." como fórmula (inyección CSV).
            if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) v = "'" + v;
            boolean quote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
            sb.append(quote ? '"' + v.replace("\"", "\"\"") + '"' : v);
        }
        sb.append("\r\n");
    }

    // ── Excel ────────────────────────────────────────────────────

    private byte[] xlsx(ReportTable t, String generatedBy) throws IOException {
        HospitalConfig hospital = hospitalConfigService.getEntity();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeSheetName(t.title()));
            DataFormat df = wb.createDataFormat();

            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 15);
            CellStyle titleStyle = wb.createCellStyle();
            titleStyle.setFont(titleFont);
            Font mutedFont = wb.createFont();
            mutedFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            CellStyle muted = wb.createCellStyle();
            muted.setFont(mutedFont);
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle bold = wb.createCellStyle();
            bold.setFont(boldFont);
            CellStyle header = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle[] body = new CellStyle[Type.values().length];
            CellStyle[] total = new CellStyle[Type.values().length];
            for (Type type : Type.values()) {
                body[type.ordinal()] = typed(wb, df, type, false);
                total[type.ordinal()] = typed(wb, df, type, true);
            }

            int r = 0;
            cell(sheet.createRow(r++), 0, hospital.getName(), muted);
            cell(sheet.createRow(r++), 0, t.title(), titleStyle);
            if (t.subtitle() != null) cell(sheet.createRow(r++), 0, t.subtitle(), muted);
            cell(sheet.createRow(r++), 0, "Generado el " + LocalDateTime.now().format(DATETIME)
                    + (generatedBy == null ? "" : " por " + generatedBy), muted);
            if (!t.summary().isEmpty()) {
                r++;
                for (ReportTable.Stat s : t.summary()) {
                    Row row = sheet.createRow(r++);
                    cell(row, 0, s.label(), bold);
                    cell(row, 1, s.value(), null);
                }
            }
            r++;
            int headerRow = r;
            Row hr = sheet.createRow(r++);
            hr.setHeightInPoints(20);
            for (int i = 0; i < t.columns().size(); i++) cell(hr, i, t.columns().get(i).label(), header);
            for (List<Object> row : t.rows()) writeRow(sheet.createRow(r++), t.columns(), row, body);
            int lastData = r - 1;
            if (t.totals() != null) writeRow(sheet.createRow(r++), t.columns(), t.totals(), total);
            if (t.note() != null) {
                r++;
                cell(sheet.createRow(r), 0, t.note(), muted);
            }

            sheet.createFreezePane(0, headerRow + 1);
            if (!t.rows().isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(headerRow, lastData, 0, t.columns().size() - 1));
            }
            for (int i = 0; i < t.columns().size(); i++) {
                Column c = t.columns().get(i);
                int width = Math.max(c.label().length() + 4, switch (c.type()) {
                    case MONEY -> 14;
                    case DATETIME -> 17;
                    case DATE -> 12;
                    case TIME, INT, PERCENT -> 10;
                    case TEXT -> Math.min(45, Math.max(14, longest(t, i) + 2));
                });
                sheet.setColumnWidth(i, width * 256);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static int longest(ReportTable t, int col) {
        int max = 0;
        for (List<Object> row : t.rows()) {
            Object v = col < row.size() ? row.get(col) : null;
            if (v != null) max = Math.max(max, v.toString().length());
        }
        return max;
    }

    private static CellStyle typed(Workbook wb, DataFormat df, Type type, boolean totals) {
        CellStyle s = wb.createCellStyle();
        switch (type) {
            case MONEY -> s.setDataFormat(df.getFormat("$#,##0.00"));
            case PERCENT -> s.setDataFormat(df.getFormat("0.0\"%\""));
            case INT -> s.setDataFormat(df.getFormat("#,##0"));
            case DATE -> s.setDataFormat(df.getFormat("dd/mm/yyyy"));
            case TIME -> s.setDataFormat(df.getFormat("hh:mm"));
            case DATETIME -> s.setDataFormat(df.getFormat("dd/mm/yyyy hh:mm"));
            default -> { }
        }
        if (totals) {
            Font f = wb.createFont();
            f.setBold(true);
            s.setFont(f);
            s.setBorderTop(BorderStyle.THIN);
        }
        return s;
    }

    private static void writeRow(Row row, List<Column> cols, List<Object> values, CellStyle[] styles) {
        for (int i = 0; i < cols.size(); i++) {
            Object v = i < values.size() ? values.get(i) : null;
            Type type = cols.get(i).type();
            Cell c = row.createCell(i);
            c.setCellStyle(styles[type.ordinal()]);
            if (v == null) continue;
            if (v instanceof Number n && type != Type.TEXT) c.setCellValue(n.doubleValue());
            else if (v instanceof LocalDateTime d) c.setCellValue(d);
            else if (v instanceof LocalDate d) c.setCellValue(d);
            else if (v instanceof LocalTime tm) c.setCellValue(tm.toSecondOfDay() / 86400.0);
            else c.setCellValue(v.toString());
        }
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        if (style != null) c.setCellStyle(style);
    }

    private static String safeSheetName(String name) {
        String s = name.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    // ── PDF ──────────────────────────────────────────────────────

    private static final float PAGE_W = PDRectangle.LETTER.getHeight();   // horizontal
    private static final float PAGE_H = PDRectangle.LETTER.getWidth();
    private static final float M = 36;
    private static final float ROW_H = 17;
    private static final float FONT = 7.8f;

    private byte[] pdf(ReportTable t, String generatedBy) throws IOException {
        HospitalConfig hospital = hospitalConfigService.getEntity();
        int[] brand = PdfKit.hexToRgb(hospital.getPrimaryColor());
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDImageXObject logo = logoLoader.load(doc, hospital.getLogoUrl());
            float tableW = PAGE_W - 2 * M;
            float totalWeight = 0;
            for (Column c : t.columns()) totalWeight += c.weight();
            float[] widths = new float[t.columns().size()];
            for (int i = 0; i < widths.length; i++) widths[i] = tableW * t.columns().get(i).weight() / totalWeight;

            PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
            doc.addPage(page);
            PDPageContentStream c = new PDPageContentStream(doc, page);
            float y = firstPageHeader(c, t, hospital, logo, brand, generatedBy);
            y = tableHeader(c, t, widths, y, brand);

            int index = 0;
            List<List<Object>> all = new ArrayList<>(t.rows());
            for (List<Object> row : all) {
                if (y - ROW_H < M + 24) {
                    c.close();
                    page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
                    doc.addPage(page);
                    c = new PDPageContentStream(doc, page);
                    y = continuationHeader(c, t, hospital, brand);
                    y = tableHeader(c, t, widths, y, brand);
                }
                if (index++ % 2 == 1) PdfKit.fill(c, M, y - ROW_H, tableW, ROW_H, PdfKit.PANEL);
                drawRow(c, t.columns(), widths, row, y, false);
                y -= ROW_H;
            }
            if (all.isEmpty()) {
                PdfKit.textCenter(c, PdfKit.ITALIC, 9, PAGE_W / 2, y - 16, "cash-cut".equals(t.key()) ? "Todavía no hay cobros en este corte." : "Sin información para los filtros elegidos.", PdfKit.MUTED);
                y -= ROW_H + 8;
            }
            if (t.totals() != null && !all.isEmpty()) {
                if (y - ROW_H < M + 24) {
                    c.close();
                    page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
                    doc.addPage(page);
                    c = new PDPageContentStream(doc, page);
                    y = continuationHeader(c, t, hospital, brand);
                    y = tableHeader(c, t, widths, y, brand);
                }
                PdfKit.line(c, M, y, M + tableW, y, PdfKit.INK_SOFT, 0.8f);
                PdfKit.fill(c, M, y - ROW_H, tableW, ROW_H, PdfKit.tint(brand, 0.9));
                drawRow(c, t.columns(), widths, t.totals(), y, true);
                y -= ROW_H;
            }
            if (t.note() != null && y - 20 > M + 24) {
                PdfKit.lines(c, PdfKit.wrap(t.note(), PdfKit.ITALIC, 7.5f, tableW, 3), PdfKit.ITALIC, 7.5f, M, y - 14, 10, PdfKit.MUTED);
            }
            c.close();

            int pages = doc.getNumberOfPages();
            String legal = hospital.getLegalName() != null ? hospital.getLegalName() : hospital.getName();
            for (int i = 0; i < pages; i++) {
                try (PDPageContentStream f = new PDPageContentStream(doc, doc.getPage(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    PdfKit.line(f, M, M + 10, PAGE_W - M, M + 10, PdfKit.LINE, 0.6f);
                    PdfKit.text(f, PdfKit.REGULAR, 7, M, M - 2, legal + (hospital.getRfc() != null ? " · RFC " + hospital.getRfc() : "")
                            + " · " + t.title(), PdfKit.FAINT);
                    PdfKit.textRight(f, PdfKit.REGULAR, 7, PAGE_W - M, M - 2, "Hoja " + (i + 1) + " de " + pages, PdfKit.FAINT);
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private float firstPageHeader(PDPageContentStream c, ReportTable t, HospitalConfig hospital, PDImageXObject logo,
                                  int[] brand, String generatedBy) throws IOException {
        PdfKit.fill(c, 0, PAGE_H - 6, PAGE_W, 6, brand);
        float top = PAGE_H - M;
        float x = M;
        if (logo != null) {
            float h = 34;
            float w = Math.min(90, logo.getWidth() * h / logo.getHeight());
            c.drawImage(logo, M, top - h + 4, w, h);
            x = M + w + 12;
        }
        PdfKit.text(c, PdfKit.BOLD, 11, x, top - 6, hospital.getName(), PdfKit.INK);
        String sub = hospital.getLegalName() != null ? hospital.getLegalName() + (hospital.getRfc() != null ? " · RFC " + hospital.getRfc() : "") : "Reporte de citas";
        PdfKit.text(c, PdfKit.REGULAR, 8, x, top - 19, sub, PdfKit.MUTED);
        PdfKit.textRight(c, PdfKit.REGULAR, 7.5f, PAGE_W - M, top - 6, "Generado el " + LocalDateTime.now().format(DATETIME), PdfKit.MUTED);
        if (generatedBy != null) PdfKit.textRight(c, PdfKit.REGULAR, 7.5f, PAGE_W - M, top - 17, "por " + generatedBy, PdfKit.MUTED);

        float y = top - 50;
        PdfKit.label(c, M, y + 4, "Reporte", brand);
        PdfKit.text(c, PdfKit.BOLD, 18, M, y - 16, t.title(), PdfKit.INK);
        y -= 30;
        if (t.subtitle() != null) {
            PdfKit.text(c, PdfKit.REGULAR, 9, M, y - 4, t.subtitle(), PdfKit.BODY);
            y -= 16;
        }
        if (!t.summary().isEmpty()) {
            y -= 6;
            int perRow = t.summary().size() <= 8 ? t.summary().size() : 6;
            float gap = 8;
            float boxW = (PAGE_W - 2 * M - gap * (perRow - 1)) / perRow;
            float boxH = 38;
            for (int i = 0; i < t.summary().size(); i++) {
                int col = i % perRow;
                if (i > 0 && col == 0) y -= boxH + gap;
                float bx = M + col * (boxW + gap);
                PdfKit.roundFill(c, bx, y - boxH, boxW, boxH, 6, PdfKit.PANEL);
                PdfKit.roundStroke(c, bx, y - boxH, boxW, boxH, 6, PdfKit.LINE, 0.6f);
                ReportTable.Stat s = t.summary().get(i);
                PdfKit.text(c, PdfKit.REGULAR, 7, bx + 9, y - 13, PdfKit.truncate(s.label().toUpperCase(MX), PdfKit.REGULAR, 7, boxW - 18), PdfKit.MUTED);
                PdfKit.text(c, PdfKit.BOLD, 12, bx + 9, y - 29, PdfKit.truncate(s.value(), PdfKit.BOLD, 12, boxW - 18), PdfKit.INK);
            }
            y -= boxH + 14;
        }
        return y - 4;
    }

    private float continuationHeader(PDPageContentStream c, ReportTable t, HospitalConfig hospital, int[] brand) throws IOException {
        PdfKit.fill(c, 0, PAGE_H - 4, PAGE_W, 4, brand);
        float top = PAGE_H - M + 6;
        PdfKit.text(c, PdfKit.BOLD, 10, M, top - 6, t.title(), PdfKit.INK);
        PdfKit.textRight(c, PdfKit.REGULAR, 7.5f, PAGE_W - M, top - 6,
                hospital.getName() + (t.subtitle() != null ? " · " + t.subtitle() : ""), PdfKit.MUTED);
        return top - 18;
    }

    private float tableHeader(PDPageContentStream c, ReportTable t, float[] widths, float y, int[] brand) throws IOException {
        float x = M;
        PdfKit.fill(c, M, y - ROW_H - 2, PAGE_W - 2 * M, ROW_H + 2, PdfKit.INK_SOFT);
        for (int i = 0; i < widths.length; i++) {
            Column col = t.columns().get(i);
            String label = PdfKit.truncate(col.label(), PdfKit.BOLD, 7.2f, widths[i] - 8);
            if (col.numeric()) PdfKit.textRight(c, PdfKit.BOLD, 7.2f, x + widths[i] - 4, y - 12, label, PdfKit.WHITE);
            else PdfKit.text(c, PdfKit.BOLD, 7.2f, x + 4, y - 12, label, PdfKit.WHITE);
            x += widths[i];
        }
        return y - ROW_H - 2;
    }

    private void drawRow(PDPageContentStream c, List<Column> cols, float[] widths, List<Object> row, float y, boolean bold) throws IOException {
        float x = M;
        var font = bold ? PdfKit.BOLD : PdfKit.REGULAR;
        for (int i = 0; i < widths.length; i++) {
            Column col = cols.get(i);
            String value = PdfKit.truncate(format(i < row.size() ? row.get(i) : null, col.type()), font, FONT, widths[i] - 8);
            int[] color = bold ? PdfKit.INK : PdfKit.BODY;
            if (col.numeric()) PdfKit.textRight(c, font, FONT, x + widths[i] - 4, y - 11.5f, value, color);
            else PdfKit.text(c, font, FONT, x + 4, y - 11.5f, value, color);
            x += widths[i];
        }
        PdfKit.line(c, M, y - ROW_H, M + sum(widths), y - ROW_H, PdfKit.LINE, 0.4f);
    }

    private static float sum(float[] values) {
        float s = 0;
        for (float v : values) s += v;
        return s;
    }
}
