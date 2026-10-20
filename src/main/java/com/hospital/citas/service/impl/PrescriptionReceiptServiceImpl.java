package com.hospital.citas.service.impl;

import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.Doctor;
import com.hospital.citas.entity.HospitalConfig;
import com.hospital.citas.entity.Prescription;
import com.hospital.citas.pdf.LogoLoader;
import com.hospital.citas.service.HospitalConfigService;
import com.hospital.citas.service.PrescriptionReceiptService;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static com.hospital.citas.pdf.PdfKit.*;

/**
 * Receta médica en PDF con formato de "hoja membretada": membrete con la marca del hospital y
 * los datos del doctor (nombre, especialidad, cédula), franja con paciente / fecha / folio /
 * tipo de sangre, diagnóstico, indicaciones con el símbolo Rx y espacio para la firma de puño y
 * letra. Una receta larga sigue en hojas nuevas con membrete compacto y "Página n de N".
 * <p>
 * Las alergias NO se imprimen (decisión de producto: el doctor las ve en pantalla al recetar,
 * pero no van en el papel que se lleva el paciente). El tipo de sangre es la foto tomada al
 * agendar esta cita (Appointment#bloodTypeSnapshot).
 */
@Service
@RequiredArgsConstructor
public class PrescriptionReceiptServiceImpl implements PrescriptionReceiptService {

    private static final Locale MX = Locale.forLanguageTag("es-MX");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", MX);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final float W = PDRectangle.A4.getWidth();
    private static final float H = PDRectangle.A4.getHeight();
    private static final float M = 46;
    private static final float CW = W - M * 2;
    private static final float BOTTOM = 70;
    private static final float BODY_SIZE = 11;
    private static final float LEADING = 16;

    private final HospitalConfigService hospitalConfigService;
    private final LogoLoader logoLoader;

    /** Hoja actual y cursor vertical de un documento que puede crecer a varias hojas. */
    private final class Flow {
        final PDDocument doc;
        final HospitalConfig hospital;
        final Doctor doctor;
        final int[] brand;
        final PDImageXObject logo;
        PDPageContentStream c;
        float y;

        Flow(PDDocument doc, HospitalConfig hospital, Doctor doctor, int[] brand) {
            this.doc = doc;
            this.hospital = hospital;
            this.doctor = doctor;
            this.brand = brand;
            this.logo = logoLoader.load(doc, hospital.getLogoUrl());
        }

        void newPage(boolean continuation) throws IOException {
            if (c != null) c.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            c = new PDPageContentStream(doc, page);
            y = continuation ? compactHeader(this) : letterhead(this);
            footer(this);
        }

        void ensure(float needed) throws IOException {
            if (y - needed < BOTTOM) newPage(true);
        }

        void finish() throws IOException {
            if (c != null) c.close();
            c = null;
        }
    }

    @Override
    public byte[] build(Prescription prescription) {
        HospitalConfig hospital = hospitalConfigService.getEntity();
        Appointment appointment = prescription.getAppointment();
        int[] brand = hexToRgb(hospital.getPrimaryColor());
        try (PDDocument doc = new PDDocument()) {
            Flow f = new Flow(doc, hospital, appointment.getDoctor(), brand);
            f.newPage(false);
            infoStrip(f, prescription, appointment);
            if (prescription.isVoided()) voided(f, prescription);
            if (prescription.getDiagnosis() != null && !prescription.getDiagnosis().isBlank()) diagnosis(f, prescription.getDiagnosis());
            indications(f, prescription.getContent());
            signature(f);
            f.finish();
            if (prescription.isVoided()) {
                for (PDPage page : doc.getPages()) {
                    try (PDPageContentStream c = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        watermark(c, "ANULADA", W, H, RED);
                    }
                }
            }
            pageNumbers(doc);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar la receta en PDF", e);
        }
    }

    // ── Membrete ─────────────────────────────────────────────────

    private float letterhead(Flow f) throws IOException {
        PDPageContentStream c = f.c;
        fill(c, 0, H - 8, W, 8, f.brand);
        float top = H - 34;
        float tile = 50;
        logoTile(f, M, top - tile, tile);
        float tx = M + tile + 14;
        text(c, BOLD, 16, tx, top - 20, truncate(f.hospital.getName(), BOLD, 16, 230), INK);
        String contact = AppointmentReceiptServiceImpl.join(" · ", f.hospital.getContactPhone(), f.hospital.getContactEmail());
        if (!contact.isEmpty()) text(c, REGULAR, 8.5f, tx, top - 36, truncate(contact, REGULAR, 8.5f, 230), MUTED);
        label(c, tx, top - 50, "Receta médica", f.brand);

        // Doctor a la derecha, como en un recetario.
        float right = W - M;
        String name = "Dr(a). " + f.doctor.getUser().getFirstName() + " " + f.doctor.getUser().getLastName();
        textRight(c, BOLD, 14, right, top - 18, truncate(name, BOLD, 14, 220), INK);
        textRight(c, REGULAR, 10, right, top - 34, f.doctor.getSpecialty().getName(), shade(f.brand, 0.15));
        String license = f.doctor.getLicenseNumber();
        textRight(c, REGULAR, 8.5f, right, top - 48,
                license != null && !license.isBlank() ? "Cédula profesional " + license : "Cédula profesional no registrada", MUTED);
        float lineY = top - tile - 14;
        line(c, M, lineY, W - M, lineY, f.brand, 1.2f);
        return lineY - 18;
    }

    private float compactHeader(Flow f) throws IOException {
        fill(f.c, 0, H - 6, W, 6, f.brand);
        float top = H - 30;
        text(f.c, BOLD, 11, M, top - 10, f.hospital.getName(), INK);
        textRight(f.c, REGULAR, 9, W - M, top - 10, "Receta médica (continuación)", MUTED);
        line(f.c, M, top - 20, W - M, top - 20, LINE, 0.8f);
        return top - 40;
    }

    private void logoTile(Flow f, float x, float y, float size) throws IOException {
        roundFill(f.c, x, y, size, size, 12, tint(f.brand, 0.9));
        if (f.logo != null) {
            float scale = Math.min((size - 8) / f.logo.getWidth(), (size - 8) / f.logo.getHeight());
            float lw = f.logo.getWidth() * scale;
            float lh = f.logo.getHeight() * scale;
            roundFill(f.c, x, y, size, size, 12, WHITE);
            roundStroke(f.c, x, y, size, size, 12, LINE, 0.8f);
            f.c.drawImage(f.logo, x + (size - lw) / 2, y + (size - lh) / 2, lw, lh);
        } else {
            textCenter(f.c, BOLD, 18, x + size / 2, y + size / 2 - 6, initials(f.hospital.getName()), shade(f.brand, 0.1));
        }
    }

    // ── Cuerpo ───────────────────────────────────────────────────

    private void infoStrip(Flow f, Prescription p, Appointment a) throws IOException {
        float h = 56;
        float y = f.y - h;
        roundFill(f.c, M, y, CW, h, 12, PANEL);
        LocalDateTime issued = p.getCreatedAt() != null ? p.getCreatedAt() : LocalDateTime.now();
        var patient = a.getPatient();
        String ageSex = AppointmentReceiptServiceImpl.join(" · ",
                patient.getBirthDate() != null ? java.time.Period.between(patient.getBirthDate(), java.time.LocalDate.now()).getYears() + " años" : null,
                patient.getSex() == null ? null : switch (patient.getSex()) { case "F" -> "Femenino"; case "M" -> "Masculino"; default -> "Otro"; });
        String[][] cols = {
                {"Paciente", patient.getFirstName() + " " + patient.getLastName()},
                {"Edad / sexo", ageSex.isEmpty() ? "—" : ageSex},
                {"Fecha", issued.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))},
                {"Folio", "RX-" + String.format("%06d", p.getId())},
                {"Tipo de sangre", AppointmentReceiptServiceImpl.bloodType(a.getBloodTypeSnapshot(), a.getBloodTypeOtherSnapshot())},
        };
        // El nombre del paciente es lo más largo: se lleva casi la mitad de la franja.
        float[] weights = {0.34f, 0.19f, 0.16f, 0.16f, 0.15f};
        float x0 = M;
        for (int i = 0; i < cols.length; i++) {
            float colW = CW * weights[i];
            float x = x0 + 16;
            label(f.c, x, y + h - 20, cols[i][0], MUTED);
            text(f.c, BOLD, 10.5f, x, y + 17, truncate(cols[i][1], BOLD, 10.5f, colW - 24), INK_SOFT);
            if (i > 0) line(f.c, x0, y + 12, x0, y + h - 12, LINE, 0.8f);
            x0 += colW;
        }
        f.y = y - 22;
    }

    private void voided(Flow f, Prescription p) throws IOException {
        String reason = "Motivo: " + (p.getVoidReason() == null ? "sin motivo" : p.getVoidReason())
                + (p.getVoidedAt() != null ? "  ·  Anulada el " + p.getVoidedAt().format(STAMP) : "");
        List<String> lines = wrap(reason, REGULAR, 9.5f, CW - 32, 3);
        float h = 38 + lines.size() * 13;
        f.ensure(h);
        float y = f.y - h;
        roundFill(f.c, M, y, CW, h, 12, RED_BG);
        roundStroke(f.c, M, y, CW, h, 12, new int[]{252, 165, 165}, 0.8f);
        text(f.c, BOLD, 12, M + 16, f.y - 22, "RECETA ANULADA - NO VIGENTE", RED);
        lines(f.c, lines, REGULAR, 9.5f, M + 16, f.y - 38, 13, RED);
        f.y = y - 18;
    }

    private void diagnosis(Flow f, String text) throws IOException {
        List<String> lines = wrap(text, REGULAR, 10.5f, CW - 36, 0);
        float h = 36 + lines.size() * 15;
        if (h > f.y - BOTTOM) {
            // Diagnóstico muy largo: sin tarjeta, como texto corrido que pagina.
            paragraphs(f, "Diagnóstico", text);
            return;
        }
        float y = f.y - h;
        roundFill(f.c, M, y, CW, h, 12, tint(f.brand, 0.94));
        fill(f.c, M, y + 10, 3, h - 20, f.brand);
        label(f.c, M + 18, f.y - 20, "Diagnóstico", shade(f.brand, 0.2));
        lines(f.c, lines, REGULAR, 10.5f, M + 18, f.y - 36, 15, INK_SOFT);
        f.y = y - 22;
    }

    private void indications(Flow f, String content) throws IOException {
        f.ensure(70);
        text(f.c, BOLD, 30, M, f.y - 26, "Rx", f.brand);
        label(f.c, M + 50, f.y - 14, "Indicaciones", MUTED);
        line(f.c, M + 50, f.y - 22, W - M, f.y - 22, LINE, 0.8f);
        f.y -= 44;
        float x = M + 50;
        float maxW = W - M - x;
        int n = 0;
        for (String paragraph : (content == null ? "" : content).split("\\R", -1)) {
            if (paragraph.isBlank()) {
                f.y -= 8;
                continue;
            }
            List<String> lines = wrap(paragraph, REGULAR, BODY_SIZE, maxW - 18, 0);
            f.ensure(LEADING + 4);
            n++;
            // Cada renglón que escribió el doctor (típicamente un medicamento) lleva su número.
            roundFill(f.c, x, f.y - 4, 13, 13, 6.5f, tint(f.brand, 0.85));
            textCenter(f.c, BOLD, 7.5f, x + 6.5f, f.y - 0.5f, String.valueOf(n), shade(f.brand, 0.2));
            for (String l : lines) {
                f.ensure(LEADING);
                text(f.c, REGULAR, BODY_SIZE, x + 20, f.y, l, INK);
                f.y -= LEADING;
            }
            f.y -= 6;
        }
    }

    private void paragraphs(Flow f, String title, String body) throws IOException {
        f.ensure(40);
        label(f.c, M, f.y, title, MUTED);
        f.y -= 18;
        for (String l : wrap(body, REGULAR, BODY_SIZE, CW, 0)) {
            f.ensure(LEADING);
            text(f.c, REGULAR, BODY_SIZE, M, f.y, l, INK);
            f.y -= LEADING;
        }
        f.y -= 14;
    }

    /** Firma de puño y letra sobre el papel (no hay firma digital), con nombre y cédula impresos. */
    private void signature(Flow f) throws IOException {
        float lineY = Math.min(150, f.y - 50);
        if (lineY - 40 < BOTTOM) {
            f.newPage(true);
            lineY = f.y - 60;
        }
        float w = 230;
        float x = W - M - w;
        line(f.c, x, lineY, x + w, lineY, INK_SOFT, 0.9f);
        String name = "Dr(a). " + f.doctor.getUser().getFirstName() + " " + f.doctor.getUser().getLastName();
        textCenter(f.c, BOLD, 10, x + w / 2, lineY - 14, truncate(name, BOLD, 10, w), INK);
        String license = f.doctor.getLicenseNumber();
        textCenter(f.c, REGULAR, 8.5f, x + w / 2, lineY - 27,
                license != null && !license.isBlank() ? "Céd. prof. " + license : f.doctor.getSpecialty().getName(), MUTED);
        text(f.c, ITALIC, 8.5f, M, lineY - 14, "Esta receta solo es válida con la firma del médico.", FAINT);
        f.y = lineY - 40;
    }

    private void footer(Flow f) throws IOException {
        line(f.c, M, 50, W - M, 50, LINE, 0.8f);
        String contact = AppointmentReceiptServiceImpl.join(" · ", f.hospital.getName(), f.hospital.getContactPhone(),
                AppointmentReceiptServiceImpl.hospitalAddress(f.hospital));
        text(f.c, REGULAR, 7.5f, M, 36, truncate(contact, REGULAR, 7.5f, CW - 90), MUTED);
        text(f.c, REGULAR, 7, M, 25, "Generada el " + LocalDateTime.now().format(STAMP) + " con Nexora Citas", FAINT);
    }

    private void pageNumbers(PDDocument doc) throws IOException {
        int total = doc.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            try (PDPageContentStream c = new PDPageContentStream(doc, doc.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                textRight(c, BOLD, 7.5f, W - M, 36, "Página " + (i + 1) + " de " + total, MUTED);
            }
        }
    }
}
