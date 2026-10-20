package com.hospital.citas.report;

import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.CashCut;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.entity.Payment;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.AppointmentPaymentStatus;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.CashCutStatus;
import com.hospital.citas.enums.PaymentMethod;
import com.hospital.citas.enums.PaymentStatus;
import com.hospital.citas.enums.RefundStatus;
import com.hospital.citas.security.Permission;
import com.hospital.citas.security.PermissionService.PermissionDeniedException;
import com.hospital.citas.exception.ResourceNotFoundException;
import com.hospital.citas.report.ReportTable.Type;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.BranchRepository;
import com.hospital.citas.repository.CashCutRepository;
import com.hospital.citas.repository.DoctorRepository;
import com.hospital.citas.repository.PaymentRepository;
import com.hospital.citas.repository.SpecialtyRepository;
import com.hospital.citas.security.DoctorScope;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.payment.PaymentAggregator;
import com.hospital.citas.service.ReportService;
import com.hospital.citas.service.ReportService.Filters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catálogo de reportes de citas (como los del hotel, pero de consultas): cada uno es una tabla
 * con su resumen, que se ve en pantalla y se baja en CSV, Excel o PDF. Los de dinero solo los
 * ve quien tiene "Ver montos en reportes"; un doctor siempre ve solo lo suyo (lo aplica
 * {@link ReportService#scoped}).
 */
@Service
@RequiredArgsConstructor
public class ReportCatalog {

    public record Definition(String key, String category, String title, String description, boolean financial) {
    }

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("appointments", "Citas", "Citas del periodo", "Todas las citas con paciente, doctor, sede, estado y pago.", false),
            new Definition("daily", "Citas", "Resumen por día", "Citas atendidas, programadas, no asistió y canceladas de cada día.", false),
            new Definition("cancellations", "Citas", "Cancelaciones", "Quién canceló, con cuánta anticipación y por qué.", false),
            new Definition("no-shows", "Citas", "Inasistencias", "Pacientes que no llegaron a su cita, con sus faltas en el periodo.", false),
            new Definition("demand", "Citas", "Demanda por hora y día", "En qué horas y días de la semana se concentran las citas.", false),
            new Definition("by-doctor", "Desempeño", "Productividad por doctor", "Citas, asistencia, pacientes e ingresos de cada doctor.", false),
            new Definition("by-specialty", "Desempeño", "Por especialidad", "Demanda, asistencia e ingresos de cada especialidad.", false),
            new Definition("by-branch", "Desempeño", "Por sede", "Demanda, asistencia e ingresos de cada sede.", false),
            new Definition("patients", "Pacientes", "Pacientes del periodo", "Quién vino, cuántas veces, si es nuevo y cuándo fue su última cita.", false),
            new Definition("payments", "Caja", "Cobros", "Cada cobro con hora, cita, paciente, método, quién cobró y corte.", true),
            new Definition("by-method", "Caja", "Por forma de pago", "Cuánto entró en efectivo, terminal y OpenPay.", true),
            new Definition("receivables", "Caja", "Por cobrar", "Citas atendidas que todavía no se pagan.", true),
            new Definition("refunds", "Caja", "Reembolsos", "Pagos devueltos o en revisión, con motivo y responsable.", true),
            new Definition("cash-cuts", "Caja", "Cortes de caja", "Cada corte con fondo, cobros por método, esperado, contado y diferencia.", true));

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale MX = Locale.forLanguageTag("es-MX");

    private final ReportService reportService;
    private final AppointmentRepository appointmentRepository;
    private final PaymentRepository paymentRepository;
    private final CashCutRepository cashCutRepository;
    private final DoctorRepository doctorRepository;
    private final BranchRepository branchRepository;
    private final SpecialtyRepository specialtyRepository;
    private final DoctorScope doctorScope;

    /** Reportes que esta persona puede abrir. */
    public List<Definition> catalog() {
        boolean money = reportService.canSeeMoney();
        return DEFINITIONS.stream().filter(d -> money || !d.financial()).toList();
    }

    @Transactional(readOnly = true)
    public ReportTable build(String key, Filters f) {
        Definition def = DEFINITIONS.stream().filter(d -> d.key().equals(key)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Reporte no encontrado: " + key));
        boolean money = reportService.canSeeMoney();
        if (def.financial() && !money) {
            throw new PermissionDeniedException(Permission.REPORTS_FINANCIAL);
        }
        ReportTable.Builder b = ReportTable.builder(def.key(), def.title()).subtitle(describe(f));
        switch (key) {
            case "appointments" -> appointments(b, f, money);
            case "daily" -> daily(b, f, money);
            case "cancellations" -> cancellations(b, f);
            case "no-shows" -> noShows(b, f);
            case "demand" -> demand(b, f);
            case "by-doctor" -> grouped(b, f, money, a -> a.getDoctor().getId(), this::doctorName, a -> a.getDoctor().getSpecialty().getName(), "Doctor", "Especialidad");
            case "by-specialty" -> grouped(b, f, money, a -> a.getDoctor().getSpecialty().getId(), a -> a.getDoctor().getSpecialty().getName(), null, "Especialidad", null);
            case "by-branch" -> grouped(b, f, money, a -> a.getBranch().getId(), a -> a.getBranch().getName(), null, "Sede", null);
            case "patients" -> patients(b, f, money);
            case "payments" -> payments(b, f);
            case "by-method" -> byMethod(b, f);
            case "receivables" -> receivables(b, f);
            case "refunds" -> refunds(b, f);
            case "cash-cuts" -> cashCuts(b, f);
            default -> throw new ResourceNotFoundException("Reporte no encontrado: " + key);
        }
        return b.build();
    }

    // ── Citas ────────────────────────────────────────────────────

    private void appointments(ReportTable.Builder b, Filters f, boolean money) {
        List<Appointment> list = sorted(reportService.appointments(f));
        b.col("folio", "Folio", Type.TEXT, 0.7f).col("date", "Fecha", Type.DATE, 0.9f).col("time", "Hora", Type.TIME, 0.6f)
                .col("patient", "Paciente", Type.TEXT, 1.8f).col("phone", "Teléfono", Type.TEXT, 1f)
                .col("doctor", "Doctor", Type.TEXT, 1.7f).col("specialty", "Especialidad", Type.TEXT, 1.3f)
                .col("branch", "Sede", Type.TEXT, 1.1f).col("status", "Estado", Type.TEXT, 0.9f)
                .col("origin", "Agendó", Type.TEXT, 0.9f).col("payment", "Pago", Type.TEXT, 0.9f);
        if (money) b.col("price", "Precio", Type.MONEY, 0.9f);
        BigDecimal total = BigDecimal.ZERO;
        for (Appointment a : list) {
            List<Object> row = new ArrayList<>(List.of(folio(a), a.getAppointmentDate(), a.getStartTime(), patientName(a.getPatient()),
                    nz(a.getPatient().getPhone()), doctorName(a), a.getDoctor().getSpecialty().getName(), a.getBranch().getName(),
                    statusLabel(a.getStatus()), a.getCreatedByUser() == null ? "En línea" : "Recepción", paymentLabel(a.getPaymentStatus())));
            if (money) {
                row.add(a.getPrice());
                if (a.getPrice() != null && a.getStatus() != AppointmentStatus.CANCELLED) total = total.add(a.getPrice());
            }
            b.row(row.toArray());
        }
        long completed = count(list, AppointmentStatus.COMPLETED);
        long noShow = count(list, AppointmentStatus.NO_SHOW);
        b.stat("Citas", fmtInt(list.size())).stat("Atendidas", fmtInt(completed)).stat("No asistió", fmtInt(noShow))
                .stat("Canceladas", fmtInt(count(list, AppointmentStatus.CANCELLED)))
                .stat("Asistencia", pct(completed, completed + noShow));
        List<Object> totals = new ArrayList<>(Collections.nCopies(11, null));
        totals.set(0, "Total");
        totals.set(3, list.size() + " citas");
        if (money) {
            totals.add(total);
            b.stat("Valor (sin canceladas)", ReportFileWriter.format(total, Type.MONEY));
        }
        b.totals(totals.toArray());
    }

    private void daily(ReportTable.Builder b, Filters f, boolean money) {
        List<Appointment> list = reportService.appointments(f);
        Map<LocalDate, BigDecimal> revenue = new HashMap<>();
        if (money) {
            for (Payment p : reportService.payments(f)) {
                if (p.getStatus() == PaymentStatus.COMPLETED) revenue.merge(p.getCreatedAt().toLocalDate(), p.getAmount(), BigDecimal::add);
            }
        }
        b.col("date", "Fecha", Type.DATE, 1f).col("weekday", "Día", Type.TEXT, 1f).col("total", "Citas", Type.INT, 0.8f)
                .col("completed", "Atendidas", Type.INT, 0.8f).col("scheduled", "Programadas", Type.INT, 0.9f)
                .col("noShow", "No asistió", Type.INT, 0.8f).col("cancelled", "Canceladas", Type.INT, 0.9f)
                .col("rate", "Asistencia", Type.PERCENT, 0.9f);
        if (money) b.col("revenue", "Cobrado", Type.MONEY, 1f);
        Map<LocalDate, List<Appointment>> byDay = list.stream().collect(Collectors.groupingBy(Appointment::getAppointmentDate));
        long[] t = new long[5];
        BigDecimal totalRevenue = BigDecimal.ZERO;
        LocalDate busiest = null;
        int busiestCount = -1;
        for (LocalDate d = f.from(); !d.isAfter(f.to()); d = d.plusDays(1)) {
            List<Appointment> day = byDay.getOrDefault(d, List.of());
            long c = count(day, AppointmentStatus.COMPLETED), s = count(day, AppointmentStatus.SCHEDULED),
                    n = count(day, AppointmentStatus.NO_SHOW), x = count(day, AppointmentStatus.CANCELLED);
            t[0] += day.size(); t[1] += c; t[2] += s; t[3] += n; t[4] += x;
            List<Object> row = new ArrayList<>(List.of(d, weekday(d.getDayOfWeek()), (long) day.size(), c, s, n, x));
            row.add(c + n == 0 ? null : rate(c, c + n));
            if (money) {
                BigDecimal r = revenue.getOrDefault(d, BigDecimal.ZERO);
                row.add(r);
                totalRevenue = totalRevenue.add(r);
            }
            if (day.size() - x > busiestCount) {
                busiestCount = (int) (day.size() - x);
                busiest = d;
            }
            b.row(row.toArray());
        }
        long days = ChronoUnit.DAYS.between(f.from(), f.to()) + 1;
        b.stat("Citas", fmtInt(t[0])).stat("Promedio por día", String.format(MX, "%.1f", (t[0] - t[4]) / (double) days))
                .stat("Día con más citas", busiest == null || busiestCount <= 0 ? "—" : busiest.format(DMY) + " (" + busiestCount + ")")
                .stat("Asistencia", pct(t[1], t[1] + t[3]));
        List<Object> totals = new ArrayList<>(List.of("Total", "", t[0], t[1], t[2], t[3], t[4]));
        totals.add(t[1] + t[3] == 0 ? null : rate(t[1], t[1] + t[3]));
        if (money) {
            totals.add(totalRevenue);
            b.stat("Cobrado", ReportFileWriter.format(totalRevenue, Type.MONEY));
        }
        b.totals(totals.toArray());
    }

    private void cancellations(ReportTable.Builder b, Filters f) {
        List<Appointment> list = sorted(reportService.appointments(f).stream().filter(a -> a.getStatus() == AppointmentStatus.CANCELLED).toList());
        b.col("folio", "Folio", Type.TEXT, 0.7f).col("date", "Fecha cita", Type.DATE, 0.9f).col("time", "Hora", Type.TIME, 0.6f)
                .col("patient", "Paciente", Type.TEXT, 1.7f).col("doctor", "Doctor", Type.TEXT, 1.6f)
                .col("by", "Canceló", Type.TEXT, 1.5f).col("at", "Cuándo", Type.DATETIME, 1.2f)
                .col("notice", "Anticipación (h)", Type.INT, 1f).col("released", "Horario liberado", Type.TEXT, 1f)
                .col("reason", "Motivo", Type.TEXT, 2.4f);
        Map<String, Long> byWho = new LinkedHashMap<>();
        long late = 0;
        for (Appointment a : list) {
            String who = cancelledBy(a);
            byWho.merge(who.contains("(") ? who.substring(who.indexOf('(') + 1, who.length() - 1) : who, 1L, Long::sum);
            Long hours = a.getCancelledAt() == null ? null
                    : Math.max(0, Duration.between(a.getCancelledAt(), a.getAppointmentDate().atTime(a.getStartTime())).toHours());
            if (hours != null && hours < 24) late++;
            b.row(folio(a), a.getAppointmentDate(), a.getStartTime(), patientName(a.getPatient()), doctorName(a), who,
                    a.getCancelledAt(), hours, Boolean.TRUE.equals(a.getSlotReleased()) ? "Sí" : "No", nz(a.getCancelReason()));
        }
        long total = reportService.appointments(f).size();
        b.stat("Canceladas", fmtInt(list.size())).stat("Del total de citas", pct(list.size(), total))
                .stat("Con menos de 24 h", fmtInt(late));
        byWho.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(3)
                .forEach(e -> b.stat("Por " + e.getKey().toLowerCase(MX), fmtInt(e.getValue())));
        b.totals("Total", null, null, list.size() + " cancelaciones", null, null, null, null, null, null);
    }

    private void noShows(ReportTable.Builder b, Filters f) {
        List<Appointment> all = reportService.appointments(f);
        List<Appointment> list = sorted(all.stream().filter(a -> a.getStatus() == AppointmentStatus.NO_SHOW).toList());
        Map<Long, Long> perPatient = list.stream().collect(Collectors.groupingBy(a -> a.getPatient().getId(), Collectors.counting()));
        b.col("folio", "Folio", Type.TEXT, 0.7f).col("date", "Fecha", Type.DATE, 0.9f).col("time", "Hora", Type.TIME, 0.6f)
                .col("patient", "Paciente", Type.TEXT, 1.8f).col("phone", "Teléfono", Type.TEXT, 1f).col("email", "Correo", Type.TEXT, 1.7f)
                .col("doctor", "Doctor", Type.TEXT, 1.6f).col("branch", "Sede", Type.TEXT, 1.1f)
                .col("times", "Faltas en el periodo", Type.INT, 1.1f);
        for (Appointment a : list) {
            b.row(folio(a), a.getAppointmentDate(), a.getStartTime(), patientName(a.getPatient()), nz(a.getPatient().getPhone()),
                    nz(a.getPatient().getEmail()), doctorName(a), a.getBranch().getName(), perPatient.get(a.getPatient().getId()));
        }
        long completed = count(all, AppointmentStatus.COMPLETED);
        b.stat("Inasistencias", fmtInt(list.size())).stat("Tasa de inasistencia", pct(list.size(), list.size() + completed))
                .stat("Pacientes distintos", fmtInt(perPatient.size()))
                .stat("Faltaron 2 o más veces", fmtInt(perPatient.values().stream().filter(v -> v >= 2).count()));
        b.totals("Total", null, null, list.size() + " inasistencias", null, null, null, null, null);
    }

    private void demand(ReportTable.Builder b, Filters f) {
        List<Appointment> list = reportService.appointments(f).stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED).toList();
        b.col("hour", "Hora", Type.TEXT, 1.1f);
        for (DayOfWeek d : DayOfWeek.values()) b.col(d.name(), weekday(d), Type.INT, 0.9f);
        b.col("total", "Total", Type.INT, 0.9f).col("share", "% del total", Type.PERCENT, 0.9f);
        long[][] grid = new long[24][7];
        for (Appointment a : list) grid[a.getStartTime().getHour()][a.getAppointmentDate().getDayOfWeek().getValue() - 1]++;
        long[] perDay = new long[7];
        int peakHour = -1;
        long peak = -1;
        for (int h = 0; h < 24; h++) {
            long rowTotal = 0;
            for (int d = 0; d < 7; d++) { rowTotal += grid[h][d]; perDay[d] += grid[h][d]; }
            if (rowTotal == 0) continue;
            if (rowTotal > peak) { peak = rowTotal; peakHour = h; }
            List<Object> row = new ArrayList<>();
            row.add(String.format("%02d:00 – %02d:59", h, h));
            for (int d = 0; d < 7; d++) row.add(grid[h][d]);
            row.add(rowTotal);
            row.add(rate(rowTotal, list.size()));
            b.row(row.toArray());
        }
        int peakDay = 0;
        for (int d = 1; d < 7; d++) if (perDay[d] > perDay[peakDay]) peakDay = d;
        List<Object> totals = new ArrayList<>();
        totals.add("Total");
        for (long v : perDay) totals.add(v);
        totals.add((long) list.size());
        totals.add(list.isEmpty() ? null : 100.0);
        b.totals(totals.toArray());
        b.stat("Citas (sin canceladas)", fmtInt(list.size()))
                .stat("Hora con más citas", peakHour < 0 ? "—" : String.format("%02d:00 (%d)", peakHour, peak))
                .stat("Día con más citas", list.isEmpty() ? "—" : weekday(DayOfWeek.of(peakDay + 1)) + " (" + perDay[peakDay] + ")");
    }

    // ── Desempeño ────────────────────────────────────────────────

    private void grouped(ReportTable.Builder b, Filters f, boolean money, Function<Appointment, Long> id,
                         Function<Appointment, String> name, Function<Appointment, String> extra, String nameLabel, String extraLabel) {
        List<Appointment> list = reportService.appointments(f);
        Map<Long, BigDecimal> revenue = new HashMap<>();
        Map<Long, Long> paidCount = new HashMap<>();
        if (money) {
            for (Payment p : reportService.payments(f)) {
                if (p.getStatus() != PaymentStatus.COMPLETED) continue;
                Long k = id.apply(p.getAppointment());
                revenue.merge(k, p.getAmount(), BigDecimal::add);
                paidCount.merge(k, 1L, Long::sum);
            }
        }
        b.col("name", nameLabel, Type.TEXT, 2f);
        if (extra != null) b.col("extra", extraLabel, Type.TEXT, 1.4f);
        b.col("total", "Citas", Type.INT, 0.7f).col("completed", "Atendidas", Type.INT, 0.8f).col("noShow", "No asistió", Type.INT, 0.8f)
                .col("cancelled", "Canceladas", Type.INT, 0.8f).col("rate", "Asistencia", Type.PERCENT, 0.8f)
                .col("patients", "Pacientes", Type.INT, 0.8f).col("online", "En línea", Type.PERCENT, 0.8f);
        if (money) b.col("revenue", "Cobrado", Type.MONEY, 1f).col("ticket", "Ticket prom.", Type.MONEY, 0.9f);

        Map<Long, List<Appointment>> groups = list.stream().collect(Collectors.groupingBy(id));
        List<Map.Entry<Long, List<Appointment>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()));
        long[] t = new long[4];
        BigDecimal totalRevenue = BigDecimal.ZERO;
        long totalPaid = 0;
        for (var e : ordered) {
            List<Appointment> g = e.getValue();
            Appointment first = g.get(0);
            long c = count(g, AppointmentStatus.COMPLETED), n = count(g, AppointmentStatus.NO_SHOW), x = count(g, AppointmentStatus.CANCELLED);
            t[0] += g.size(); t[1] += c; t[2] += n; t[3] += x;
            List<Object> row = new ArrayList<>();
            row.add(name.apply(first));
            if (extra != null) row.add(extra.apply(first));
            row.addAll(List.of((long) g.size(), c, n, x));
            row.add(c + n == 0 ? null : rate(c, c + n));
            row.add(g.stream().map(a -> a.getPatient().getId()).distinct().count());
            row.add(rate(g.stream().filter(a -> a.getCreatedByUser() == null).count(), g.size()));
            if (money) {
                BigDecimal r = revenue.getOrDefault(e.getKey(), BigDecimal.ZERO);
                long pc = paidCount.getOrDefault(e.getKey(), 0L);
                row.add(r);
                row.add(pc == 0 ? null : r.divide(BigDecimal.valueOf(pc), 2, RoundingMode.HALF_UP));
                totalRevenue = totalRevenue.add(r);
                totalPaid += pc;
            }
            b.row(row.toArray());
        }
        List<Object> totals = new ArrayList<>();
        totals.add("Total");
        if (extra != null) totals.add(null);
        totals.addAll(List.of(t[0], t[1], t[2], t[3]));
        totals.add(t[1] + t[2] == 0 ? null : rate(t[1], t[1] + t[2]));
        totals.add(list.stream().map(a -> a.getPatient().getId()).distinct().count());
        totals.add(rate(list.stream().filter(a -> a.getCreatedByUser() == null).count(), list.size()));
        b.stat(nameLabel.equals("Doctor") ? "Doctores" : nameLabel.equals("Sede") ? "Sedes" : "Especialidades", fmtInt(ordered.size()))
                .stat("Citas", fmtInt(t[0])).stat("Asistencia", pct(t[1], t[1] + t[2]));
        if (!ordered.isEmpty()) b.stat("Con más citas", name.apply(ordered.get(0).getValue().get(0)));
        if (money) {
            totals.add(totalRevenue);
            totals.add(totalPaid == 0 ? null : totalRevenue.divide(BigDecimal.valueOf(totalPaid), 2, RoundingMode.HALF_UP));
            b.stat("Cobrado", ReportFileWriter.format(totalRevenue, Type.MONEY));
        }
        b.totals(totals.toArray());
    }

    // ── Pacientes ────────────────────────────────────────────────

    private void patients(ReportTable.Builder b, Filters f, boolean money) {
        List<Appointment> list = reportService.appointments(f);
        Map<Long, List<Appointment>> byPatient = list.stream().collect(Collectors.groupingBy(a -> a.getPatient().getId()));
        Map<Long, LocalDate> firstEver = new HashMap<>();
        if (!byPatient.isEmpty()) {
            for (Object[] row : appointmentRepository.firstAppointmentDates(byPatient.keySet())) firstEver.put((Long) row[0], (LocalDate) row[1]);
        }
        Map<Long, BigDecimal> paid = new HashMap<>();
        if (money) {
            for (Payment p : reportService.payments(f)) {
                if (p.getStatus() == PaymentStatus.COMPLETED) paid.merge(p.getAppointment().getPatient().getId(), p.getAmount(), BigDecimal::add);
            }
        }
        b.col("patient", "Paciente", Type.TEXT, 1.9f).col("phone", "Teléfono", Type.TEXT, 1f).col("email", "Correo", Type.TEXT, 1.8f)
                .col("age", "Edad", Type.INT, 0.5f).col("total", "Citas", Type.INT, 0.6f).col("completed", "Atendidas", Type.INT, 0.8f)
                .col("noShow", "No asistió", Type.INT, 0.8f).col("first", "Primera vez", Type.DATE, 0.9f)
                .col("last", "Última cita", Type.DATE, 0.9f).col("isNew", "Nuevo", Type.TEXT, 0.6f);
        if (money) b.col("paid", "Pagado", Type.MONEY, 0.9f);
        List<List<Appointment>> groups = new ArrayList<>(byPatient.values());
        groups.sort(Comparator.comparing((List<Appointment> g) -> patientName(g.get(0).getPatient()), String.CASE_INSENSITIVE_ORDER));
        long newOnes = 0, returning = 0;
        BigDecimal totalPaid = BigDecimal.ZERO;
        for (List<Appointment> g : groups) {
            Patient p = g.get(0).getPatient();
            LocalDate first = firstEver.get(p.getId());
            boolean isNew = first != null && !first.isBefore(f.from());
            if (isNew) newOnes++;
            else returning++;
            LocalDate last = g.stream().map(Appointment::getAppointmentDate).max(Comparator.naturalOrder()).orElse(null);
            List<Object> row = new ArrayList<>(Arrays.asList(patientName(p), nz(p.getPhone()), nz(p.getEmail()),
                    p.getBirthDate() == null ? null : (long) Period.between(p.getBirthDate(), LocalDate.now()).getYears(),
                    (long) g.size(), count(g, AppointmentStatus.COMPLETED), count(g, AppointmentStatus.NO_SHOW), first, last,
                    isNew ? "Sí" : "No"));
            if (money) {
                BigDecimal amount = paid.getOrDefault(p.getId(), BigDecimal.ZERO);
                row.add(amount);
                totalPaid = totalPaid.add(amount);
            }
            b.row(row.toArray());
        }
        b.stat("Pacientes", fmtInt(groups.size())).stat("Nuevos", fmtInt(newOnes)).stat("Regresaron", fmtInt(returning))
                .stat("Citas por paciente", groups.isEmpty() ? "—" : String.format(MX, "%.1f", list.size() / (double) groups.size()));
        List<Object> totals = new ArrayList<>(Arrays.asList("Total", groups.size() + " pacientes", null, null, (long) list.size(),
                count(list, AppointmentStatus.COMPLETED), count(list, AppointmentStatus.NO_SHOW), null, null, newOnes + " nuevos"));
        if (money) {
            totals.add(totalPaid);
            b.stat("Pagado", ReportFileWriter.format(totalPaid, Type.MONEY));
        }
        b.totals(totals.toArray());
    }

    // ── Caja ─────────────────────────────────────────────────────

    private void payments(ReportTable.Builder b, Filters f) {
        List<Payment> list = new ArrayList<>(reportService.payments(f));
        list.sort(Comparator.comparing(Payment::getCreatedAt));
        b.col("at", "Cobrado el", Type.DATETIME, 1.1f).col("folio", "Folio cita", Type.TEXT, 0.7f)
                .col("apptDate", "Fecha cita", Type.DATE, 0.85f).col("patient", "Paciente", Type.TEXT, 1.7f)
                .col("doctor", "Doctor", Type.TEXT, 1.6f).col("method", "Método", Type.TEXT, 1.1f)
                .col("channel", "Canal", Type.TEXT, 0.8f).col("reference", "Referencia", Type.TEXT, 1f)
                .col("by", "Cobró", Type.TEXT, 1.2f).col("cut", "Corte", Type.TEXT, 0.6f)
                .col("status", "Estado", Type.TEXT, 0.9f).col("amount", "Monto", Type.MONEY, 0.9f);
        BigDecimal total = BigDecimal.ZERO, refunded = BigDecimal.ZERO;
        for (Payment p : list) {
            Appointment a = p.getAppointment();
            boolean ok = p.getStatus() == PaymentStatus.COMPLETED;
            if (ok) total = total.add(p.getAmount());
            else refunded = refunded.add(p.getAmount());
            b.row(p.getCreatedAt(), folio(a), a.getAppointmentDate(), patientName(a.getPatient()), doctorName(a), methodLabel(p.getMethod()),
                    p.getChannel() == null ? "" : p.getChannel().name().equals("ONLINE") ? "En línea" : "Recepción",
                    nz(p.getReference() != null ? p.getReference() : p.getAuthorizationCode()),
                    p.getReceivedBy() == null ? (p.getChannel() != null && p.getChannel().name().equals("ONLINE") ? "Paciente (en línea)" : "")
                            : p.getReceivedBy().getFirstName() + " " + p.getReceivedBy().getLastName(),
                    p.getCashCut() == null ? "" : "#" + p.getCashCut().getId(), ok ? "Pagado" : "Reembolsado", p.getAmount());
        }
        long count = list.stream().filter(p -> p.getStatus() == PaymentStatus.COMPLETED).count();
        b.stat("Cobros", fmtInt(count)).stat("Total cobrado", ReportFileWriter.format(total, Type.MONEY))
                .stat("Ticket promedio", count == 0 ? "—" : ReportFileWriter.format(total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP), Type.MONEY))
                .stat("Reembolsado", ReportFileWriter.format(refunded, Type.MONEY));
        b.totals("Total", null, null, count + " cobros", null, null, null, null, null, null, null, total);
        b.note("El total suma solo los cobros vigentes; los reembolsados aparecen tachados como \"Reembolsado\" y no cuentan.");
    }

    private void byMethod(ReportTable.Builder b, Filters f) {
        List<Payment> list = reportService.payments(f).stream().filter(p -> p.getStatus() == PaymentStatus.COMPLETED).toList();
        BigDecimal total = list.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        b.col("method", "Forma de pago", Type.TEXT, 2f).col("count", "Cobros", Type.INT, 1f).col("amount", "Monto", Type.MONEY, 1.2f)
                .col("share", "% del total", Type.PERCENT, 1f).col("avg", "Promedio", Type.MONEY, 1.1f);
        for (PaymentMethod m : PaymentMethod.values()) {
            List<Payment> g = list.stream().filter(p -> p.getMethod() == m).toList();
            if (g.isEmpty()) continue;
            BigDecimal amount = g.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            b.row(methodLabel(m), (long) g.size(), amount,
                    total.signum() == 0 ? 0.0 : amount.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP).doubleValue(),
                    amount.divide(BigDecimal.valueOf(g.size()), 2, RoundingMode.HALF_UP));
        }
        b.stat("Cobros", fmtInt(list.size())).stat("Total cobrado", ReportFileWriter.format(total, Type.MONEY))
                .stat("Efectivo", ReportFileWriter.format(PaymentAggregator.sumMethod(list, PaymentMethod.CASH), Type.MONEY))
                .stat("Terminal", ReportFileWriter.format(PaymentAggregator.sumMethod(list, PaymentMethod.CARD_TERMINAL), Type.MONEY))
                .stat("OpenPay", ReportFileWriter.format(PaymentAggregator.sumOpenpay(list), Type.MONEY));
        b.totals("Total", (long) list.size(), total, list.isEmpty() ? null : 100.0,
                list.isEmpty() ? null : total.divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.HALF_UP));
    }

    private void receivables(ReportTable.Builder b, Filters f) {
        List<Appointment> list = sorted(reportService.appointments(f).stream()
                .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED && a.getPrice() != null && a.getPrice().signum() > 0
                        && (a.getPaymentStatus() == AppointmentPaymentStatus.UNPAID || a.getPaymentStatus() == AppointmentPaymentStatus.PENDING))
                .toList());
        b.col("folio", "Folio", Type.TEXT, 0.7f).col("date", "Fecha", Type.DATE, 0.9f).col("time", "Hora", Type.TIME, 0.6f)
                .col("patient", "Paciente", Type.TEXT, 1.8f).col("phone", "Teléfono", Type.TEXT, 1f)
                .col("doctor", "Doctor", Type.TEXT, 1.7f).col("branch", "Sede", Type.TEXT, 1.1f)
                .col("days", "Días sin pagar", Type.INT, 0.9f).col("amount", "Monto", Type.MONEY, 0.9f);
        BigDecimal total = BigDecimal.ZERO;
        long older = 0;
        for (Appointment a : list) {
            long days = ChronoUnit.DAYS.between(a.getAppointmentDate(), LocalDate.now());
            if (days > 7) older++;
            total = total.add(a.getPrice());
            b.row(folio(a), a.getAppointmentDate(), a.getStartTime(), patientName(a.getPatient()), nz(a.getPatient().getPhone()),
                    doctorName(a), a.getBranch().getName(), days, a.getPrice());
        }
        b.stat("Citas por cobrar", fmtInt(list.size())).stat("Monto pendiente", ReportFileWriter.format(total, Type.MONEY))
                .stat("Con más de 7 días", fmtInt(older));
        b.totals("Total", null, null, list.size() + " citas", null, null, null, null, total);
    }

    private void refunds(ReportTable.Builder b, Filters f) {
        List<Payment> list = reportService.payments(f).stream()
                .filter(p -> p.getStatus() == PaymentStatus.REFUNDED || (p.getRefundStatus() != null && p.getRefundStatus() != RefundStatus.NONE))
                .sorted(Comparator.comparing(Payment::getCreatedAt)).toList();
        b.col("paidAt", "Cobrado el", Type.DATETIME, 1.1f).col("folio", "Folio cita", Type.TEXT, 0.7f)
                .col("patient", "Paciente", Type.TEXT, 1.7f).col("doctor", "Doctor", Type.TEXT, 1.6f)
                .col("method", "Método", Type.TEXT, 1.1f).col("state", "Situación", Type.TEXT, 1f)
                .col("refundedAt", "Devuelto el", Type.DATETIME, 1.1f).col("by", "Autorizó", Type.TEXT, 1.2f)
                .col("reason", "Motivo", Type.TEXT, 2f).col("amount", "Monto", Type.MONEY, 0.9f);
        BigDecimal done = BigDecimal.ZERO, review = BigDecimal.ZERO;
        for (Payment p : list) {
            boolean refunded = p.getStatus() == PaymentStatus.REFUNDED || p.getRefundStatus() == RefundStatus.REFUNDED;
            if (refunded) done = done.add(p.getAmount());
            else review = review.add(p.getAmount());
            Appointment a = p.getAppointment();
            b.row(p.getCreatedAt(), folio(a), patientName(a.getPatient()), doctorName(a), methodLabel(p.getMethod()),
                    refunded ? "Devuelto" : "En revisión", p.getRefundedAt(),
                    p.getRefundedBy() == null ? "" : p.getRefundedBy().getFirstName() + " " + p.getRefundedBy().getLastName(),
                    nz(p.getRefundReason()), p.getAmount());
        }
        b.stat("Casos", fmtInt(list.size())).stat("Devuelto", ReportFileWriter.format(done, Type.MONEY))
                .stat("En revisión", ReportFileWriter.format(review, Type.MONEY));
        b.totals("Total", null, list.size() + " casos", null, null, null, null, null, null, done.add(review));
    }

    private void cashCuts(ReportTable.Builder b, Filters f) {
        User me = SecurityUtils.getCurrentUserOrNull();
        boolean doctor = doctorScope.doctorIdOf(me).isPresent();
        List<CashCut> cuts = cashCutRepository.findAll().stream()
                .filter(c -> !c.getOpenedAt().toLocalDate().isBefore(f.from()) && !c.getOpenedAt().toLocalDate().isAfter(f.to()))
                .filter(c -> !doctor || c.getUser().getId().equals(me.getId()))
                .sorted(Comparator.comparing(CashCut::getOpenedAt)).toList();
        b.col("id", "Corte", Type.TEXT, 0.6f).col("user", "Responsable", Type.TEXT, 1.5f).col("opened", "Apertura", Type.DATETIME, 1.1f)
                .col("closed", "Cierre", Type.DATETIME, 1.1f).col("status", "Estado", Type.TEXT, 0.7f)
                .col("fund", "Fondo", Type.MONEY, 0.9f).col("cash", "Efectivo", Type.MONEY, 0.9f).col("card", "Terminal", Type.MONEY, 0.9f)
                .col("openpay", "OpenPay", Type.MONEY, 0.9f).col("total", "Total", Type.MONEY, 0.9f).col("count", "Citas", Type.INT, 0.6f)
                .col("expected", "Esperado", Type.MONEY, 0.9f).col("counted", "Contado", Type.MONEY, 0.9f).col("diff", "Diferencia", Type.MONEY, 0.9f);
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        long appts = 0, withDiff = 0;
        for (CashCut c : cuts) {
            boolean closed = c.getStatus() == CashCutStatus.CLOSED;
            List<Payment> payments = closed ? List.of() : paymentRepository.findByCashCutWithDetails(c.getId());
            BigDecimal cash = closed ? zero(c.getTotalCash()) : PaymentAggregator.sumMethod(payments, PaymentMethod.CASH);
            BigDecimal card = closed ? zero(c.getTotalCardTerminal()) : PaymentAggregator.sumMethod(payments, PaymentMethod.CARD_TERMINAL);
            BigDecimal openpay = closed ? zero(c.getTotalOpenpay()) : PaymentAggregator.sumOpenpay(payments);
            BigDecimal total = cash.add(card).add(openpay);
            long count = closed ? (c.getAppointmentsCount() == null ? 0 : c.getAppointmentsCount()) : PaymentAggregator.distinctAppointments(payments);
            BigDecimal expected = closed ? c.getExpectedCash() : c.getOpeningAmount().add(cash);
            if (closed && c.getDifference() != null && c.getDifference().signum() != 0) withDiff++;
            t[0] = t[0].add(cash); t[1] = t[1].add(card); t[2] = t[2].add(openpay); t[3] = t[3].add(total);
            if (c.getDifference() != null) t[4] = t[4].add(c.getDifference());
            appts += count;
            b.row("#" + c.getId(), c.getUser().getFirstName() + " " + c.getUser().getLastName(), c.getOpenedAt(), c.getClosedAt(),
                    closed ? "Cerrado" : "Abierto", c.getOpeningAmount(), cash, card, openpay, total, count, expected,
                    c.getCountedCash(), c.getDifference());
        }
        b.stat("Cortes", fmtInt(cuts.size())).stat("Total cobrado", ReportFileWriter.format(t[3], Type.MONEY))
                .stat("Efectivo", ReportFileWriter.format(t[0], Type.MONEY))
                .stat("Con diferencia", fmtInt(withDiff)).stat("Diferencia neta", ReportFileWriter.format(t[4], Type.MONEY));
        b.totals("Total", cuts.size() + " cortes", null, null, null, null, t[0], t[1], t[2], t[3], appts, null, null, t[4]);
    }

    // ── Ayudas ───────────────────────────────────────────────────

    private String describe(Filters f) {
        StringBuilder s = new StringBuilder("Del ").append(f.from().format(DMY)).append(" al ").append(f.to().format(DMY));
        if (f.doctorId() != null) doctorRepository.findById(f.doctorId())
                .ifPresent(d -> s.append(" · Dr(a). ").append(d.getUser().getFirstName()).append(' ').append(d.getUser().getLastName()));
        if (f.specialtyId() != null) specialtyRepository.findById(f.specialtyId()).ifPresent(x -> s.append(" · ").append(x.getName()));
        if (f.branchId() != null) branchRepository.findById(f.branchId()).ifPresent(x -> s.append(" · ").append(x.getName()));
        return s.toString();
    }

    private static List<Appointment> sorted(List<Appointment> list) {
        List<Appointment> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparing(Appointment::getAppointmentDate).thenComparing(Appointment::getStartTime));
        return copy;
    }

    private static long count(List<Appointment> list, AppointmentStatus status) {
        return list.stream().filter(a -> a.getStatus() == status).count();
    }

    private static double rate(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "—" : ReportFileWriter.format(rate(part, whole), Type.PERCENT);
    }

    private static String fmtInt(long v) {
        return ReportFileWriter.format(v, Type.INT);
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String folio(Appointment a) {
        return "#" + String.format("%06d", a.getId());
    }

    private String doctorName(Appointment a) {
        return "Dr(a). " + a.getDoctor().getUser().getFirstName() + " " + a.getDoctor().getUser().getLastName();
    }

    private static String patientName(Patient p) {
        return (nz(p.getFirstName()) + " " + nz(p.getLastName())).trim();
    }

    private static String weekday(DayOfWeek d) {
        String s = d.getDisplayName(TextStyle.FULL, MX);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String cancelledBy(Appointment a) {
        User u = a.getCancelledBy();
        if (u == null) return "Paciente (en línea)";
        String role = switch (u.getRole().getName()) {
            case PATIENT -> "Paciente";
            case RECEPTIONIST -> "Recepción";
            case DOCTOR -> "Doctor";
            case ADMIN -> "Administración";
            default -> u.getRole().getName().name();
        };
        return u.getFirstName() + " " + u.getLastName() + " (" + role + ")";
    }

    static String statusLabel(AppointmentStatus s) {
        return switch (s) {
            case SCHEDULED -> "Programada";
            case COMPLETED -> "Atendida";
            case CANCELLED -> "Cancelada";
            case NO_SHOW -> "No asistió";
        };
    }

    private static String paymentLabel(AppointmentPaymentStatus s) {
        if (s == null) return "";
        return switch (s) {
            case UNPAID -> "Sin pagar";
            case PENDING -> "Pago pendiente";
            case PAID -> "Pagada";
            case REFUNDED -> "Reembolsada";
        };
    }

    public static String methodLabel(PaymentMethod m) {
        if (m == null) return "";
        return switch (m) {
            case CASH -> "Efectivo";
            case CARD_TERMINAL -> "Tarjeta (terminal)";
            case OPENPAY_CARD -> "OpenPay tarjeta";
            case OPENPAY_SPEI -> "OpenPay SPEI";
        };
    }
}
