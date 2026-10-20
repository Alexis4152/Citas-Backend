package com.hospital.citas.service;

import com.hospital.citas.entity.Payment;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Reporte del periodo en Excel: una hoja por vista (resumen, día, doctor, especialidad, sede, pagos). */
@Service
@RequiredArgsConstructor
public class ReportExcelExporter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ReportService reportService;

    @SuppressWarnings("unchecked")
    public byte[] export(ReportService.Filters filters) {
        Map<String, Object> data = reportService.overview(filters);
        boolean money = Boolean.TRUE.equals(data.get("financial"));
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);

            Sheet summary = wb.createSheet("Resumen");
            int r = 0;
            Row title = summary.createRow(r++);
            cell(title, 0, "Reporte de operación", st.title);
            cell(summary.createRow(r++), 0, "Periodo: " + data.get("from") + " a " + data.get("to"), null);
            r++;
            Map<String, Object> k = (Map<String, Object>) data.get("kpis");
            String[][] labels = {
                    {"appointments", "Citas"}, {"completed", "Atendidas"}, {"scheduled", "Programadas"},
                    {"cancelled", "Canceladas"}, {"noShow", "No asistió"}, {"patients", "Pacientes atendidos"},
                    {"newPatients", "Pacientes nuevos"}, {"completionRate", "Asistencia (%)"}, {"noShowRate", "Inasistencia (%)"},
                    {"cancellationRate", "Cancelación (%)"}, {"onlineShare", "Agendadas en línea (%)"},
                    {"revenue", "Ingresos"}, {"averageTicket", "Ticket promedio"}, {"refunded", "Reembolsado"},
                    {"pendingToCollect", "Atendidas sin cobrar"}};
            for (String[] l : labels) {
                if (!k.containsKey(l[0])) continue;
                Row row = summary.createRow(r++);
                cell(row, 0, l[1], st.bold);
                value(row, 1, k.get(l[0]), l[0].equals("revenue") || l[0].equals("averageTicket") || l[0].equals("refunded")
                        || l[0].equals("pendingToCollect") ? st.money : null);
            }
            summary.setColumnWidth(0, 32 * 256);
            summary.setColumnWidth(1, 18 * 256);

            table(wb, st, "Por día", (List<Map<String, Object>>) data.get("daily"), money,
                    new String[][]{{"date", "Fecha"}, {"completed", "Atendidas"}, {"scheduled", "Programadas"},
                            {"cancelled", "Canceladas"}, {"noShow", "No asistió"}, {"revenue", "Ingresos"}});
            String[][] groupCols = {{"name", "Nombre"}, {"appointments", "Citas"}, {"completed", "Atendidas"},
                    {"noShow", "No asistió"}, {"cancelled", "Canceladas"}, {"noShowRate", "Inasistencia (%)"}, {"revenue", "Ingresos"}};
            table(wb, st, "Por doctor", (List<Map<String, Object>>) data.get("byDoctor"), money, groupCols);
            table(wb, st, "Por especialidad", (List<Map<String, Object>>) data.get("bySpecialty"), money, groupCols);
            table(wb, st, "Por sede", (List<Map<String, Object>>) data.get("byBranch"), money, groupCols);

            if (money) {
                Sheet pays = wb.createSheet("Pagos");
                String[] head = {"Fecha", "Paciente", "Doctor", "Especialidad", "Método", "Canal", "Monto", "Estado"};
                Row h = pays.createRow(0);
                for (int i = 0; i < head.length; i++) cell(h, i, head[i], st.header);
                int pr = 1;
                for (Payment p : reportService.payments(filters)) {
                    Row row = pays.createRow(pr++);
                    cell(row, 0, p.getCreatedAt().format(STAMP), null);
                    cell(row, 1, p.getAppointment().getPatient().getFirstName() + " " + p.getAppointment().getPatient().getLastName(), null);
                    cell(row, 2, p.getAppointment().getDoctor().getUser().getFirstName() + " " + p.getAppointment().getDoctor().getUser().getLastName(), null);
                    cell(row, 3, p.getAppointment().getDoctor().getSpecialty().getName(), null);
                    cell(row, 4, p.getMethod().name(), null);
                    cell(row, 5, p.getChannel() != null ? p.getChannel().name() : "", null);
                    value(row, 6, p.getAmount(), st.money);
                    cell(row, 7, p.getStatus().name(), null);
                }
                for (int i = 0; i < head.length; i++) pays.setColumnWidth(i, (i == 1 || i == 2 ? 28 : 16) * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el Excel del reporte", e);
        }
    }

    private void table(Workbook wb, Styles st, String name, List<Map<String, Object>> rows, boolean money, String[][] cols) {
        Sheet sheet = wb.createSheet(name);
        Row h = sheet.createRow(0);
        int c = 0;
        for (String[] col : cols) {
            if (col[0].equals("revenue") && !money) continue;
            cell(h, c++, col[1], st.header);
        }
        int r = 1;
        for (Map<String, Object> data : rows) {
            Row row = sheet.createRow(r++);
            c = 0;
            for (String[] col : cols) {
                if (col[0].equals("revenue") && !money) continue;
                value(row, c++, data.get(col[0]), col[0].equals("revenue") ? st.money : null);
            }
        }
        for (int i = 0; i < c; i++) sheet.setColumnWidth(i, (i == 0 ? 30 : 15) * 256);
        sheet.createFreezePane(0, 1);
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) cell.setCellStyle(style);
    }

    private static void value(Row row, int col, Object value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value instanceof BigDecimal b) cell.setCellValue(b.doubleValue());
        else if (value instanceof Number n) cell.setCellValue(n.doubleValue());
        else cell.setCellValue(value == null ? "" : value.toString());
        if (style != null) cell.setCellStyle(style);
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle bold;
        final CellStyle money;

        Styles(Workbook wb) {
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle();
            title.setFont(big);
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            bold = wb.createCellStyle();
            bold.setFont(boldFont);
            Font white = wb.createFont();
            white.setBold(true);
            white.setColor(IndexedColors.WHITE.getIndex());
            header = wb.createCellStyle();
            header.setFont(white);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("$#,##0.00"));
        }
    }
}
