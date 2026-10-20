package com.hospital.citas;

import com.hospital.citas.entity.*;
import com.hospital.citas.enums.AppointmentPaymentStatus;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.BloodType;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.PrescriptionRepository;
import com.hospital.citas.service.AppointmentReceiptService;
import com.hospital.citas.service.PrescriptionReceiptService;
import com.hospital.citas.service.mail.EmailTemplate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Genera el comprobante, la receta y un correo de ejemplo (target/previews) y verifica que
 * los PDF sean válidos, incluida una receta larga de varias hojas y una cita cancelada. */
class DocumentPreviewTest extends AbstractIntegrationTest {

    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private AppointmentReceiptService receiptService;
    @Autowired private PrescriptionReceiptService prescriptionService;

    private Appointment appointment(AppointmentStatus status) {
        Branch branch = createBranch("Sede Centro");
        Specialty specialty = createSpecialty("Ginecología");
        specialty.setRecommendations(String.join(" • ", java.util.Collections.nCopies(9,
                "Evita relaciones sexuales las 24 a 48 horas previas a la cita, ya que el semen o lubricantes pueden alterar los resultados de las pruebas")));
        specialtyRepository.save(specialty);
        Doctor doctor = createDoctorWithFullWeekSchedule(branch, specialty);
        Patient patient = patientRepository.save(Patient.builder().firstName("María José").lastName("Hernández Núñez")
                .phone("5512345678").email("maria@correo.com").allergies("Penicilina").bloodType(BloodType.O_POSITIVE).build());
        return appointmentRepository.save(Appointment.builder().doctor(doctor).branch(branch).patient(patient)
                .appointmentDate(LocalDate.now().plusDays(3)).startTime(LocalTime.of(10, 30)).endTime(LocalTime.of(11, 0))
                .status(status).reasonForVisit("Dolor de pecho al hacer ejercicio desde hace dos semanas, con falta de aire.")
                .slotReleased(status == AppointmentStatus.CANCELLED).cancelToken(UUID.randomUUID()).overbooked(false)
                .price(new BigDecimal("850.00")).paymentStatus(AppointmentPaymentStatus.PAID)
                .cancelReason(status == AppointmentStatus.CANCELLED ? "Me surgió un viaje de trabajo" : null)
                .allergiesSnapshot("Penicilina").bloodTypeSnapshot(BloodType.O_POSITIVE).build());
    }

    @Test
    void rendersReceiptsPrescriptionsAndEmails() throws Exception {
        Path dir = Files.createDirectories(Path.of("target/previews"));
        Appointment scheduled = appointmentRepository.findByIdWithDetails(appointment(AppointmentStatus.SCHEDULED).getId()).orElseThrow();
        Appointment cancelled = appointmentRepository.findByIdWithDetails(appointment(AppointmentStatus.CANCELLED).getId()).orElseThrow();

        byte[] receipt = receiptService.build(scheduled);
        byte[] cancelledReceipt = receiptService.build(cancelled);
        Files.write(dir.resolve("comprobante.pdf"), receipt);
        Files.write(dir.resolve("comprobante-cancelado.pdf"), cancelledReceipt);

        StringBuilder longContent = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            longContent.append("Medicamento ").append(i).append(" 500 mg: tomar una tableta cada 8 horas durante 7 días, después de los alimentos.\n");
        }
        Prescription rx = prescriptionRepository.save(Prescription.builder().appointment(scheduled)
                .diagnosis("Angina de pecho estable. Se solicita prueba de esfuerzo y perfil de lípidos.")
                .content(longContent.toString()).build());
        byte[] prescription = prescriptionService.build(prescriptionRepository.findByIdWithDetails(rx.getId()).orElseThrow());
        Files.write(dir.resolve("receta.pdf"), prescription);

        // Las recomendaciones largas ya no se enciman con el pie: siguen en una segunda hoja.
        try (PDDocument doc = PDDocument.load(receipt)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
        try (PDDocument doc = PDDocument.load(cancelledReceipt)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
        try (PDDocument doc = PDDocument.load(prescription)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
        }

        String html = new EmailTemplate("#0f766e")
                .preheader("Dr(a). Pérez · jueves 22 de octubre a las 10:30")
                .hero("&#10003;", EmailTemplate.Tone.SUCCESS, "Cita confirmada", "¡Listo, María José! Tu cita quedó agendada",
                        "Te esperamos en la fecha y hora de abajo.")
                .appointmentCard(scheduled, false)
                .rows(List.of(new String[]{"Folio", "#000123"}, new String[]{"Consulta", "$850.00 MXN"}))
                .button("Ver, reprogramar o cancelar", "https://citas.example.com/c/demo/cancelar-cita/x")
                .checklist("Antes de tu cita", List.of("Llega 15 minutos antes", "Trae una identificación oficial"))
                .build("Hospital Central", null, "&#9742; 5555550101", "Recibes este correo porque agendaste una cita.");
        Files.writeString(dir.resolve("correo-confirmacion.html"), html);
        assertThat(EmailTemplate.toPlainText(html)).contains("Tu cita quedó agendada").doesNotContain("<td");
    }
}
