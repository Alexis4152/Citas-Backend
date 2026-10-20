package com.hospital.citas.service;

import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.Payment;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.AppointmentStatus;
import com.hospital.citas.enums.PaymentStatus;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.repository.AppointmentRepository;
import com.hospital.citas.repository.PaymentRepository;
import com.hospital.citas.security.DoctorScope;
import com.hospital.citas.security.Permission;
import com.hospital.citas.security.PermissionService;
import com.hospital.citas.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/**
 * Reportes de operación del hospital (patrón de nexora-hotel): indicadores del periodo, serie
 * por día y desgloses por doctor, especialidad, sede, método de pago, hora y día de la semana.
 * <p>
 * Un doctor solo ve lo suyo. Los montos solo aparecen con el permiso REPORTS_FINANCIAL.
 * Todo se calcula en memoria sobre las citas del rango (máx. 366 días), suficiente para el
 * volumen de un hospital; con mucho más volumen convendría agregarlo en SQL.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int MAX_DAYS = 366;

    private final AppointmentRepository appointmentRepository;
    private final PaymentRepository paymentRepository;
    private final DoctorScope doctorScope;
    private final PermissionService permissionService;

    public record Filters(LocalDate from, LocalDate to, Long doctorId, Long branchId, Long specialtyId) {
    }

    /** Aplica el alcance del usuario: un doctor siempre se limita a su propia agenda. */
    public Filters scoped(LocalDate from, LocalDate to, Long doctorId, Long branchId, Long specialtyId) {
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate t = to != null ? to : LocalDate.now();
        if (t.isBefore(f)) throw new BusinessException("La fecha final no puede ser anterior a la inicial");
        if (f.plusDays(MAX_DAYS).isBefore(t)) throw new BusinessException("El periodo máximo de un reporte es de un año");
        User user = SecurityUtils.getCurrentUserOrNull();
        Long own = doctorScope.doctorIdOf(user).orElse(null);
        return new Filters(f, t, own != null ? own : doctorId, branchId, specialtyId);
    }

    public boolean canSeeMoney() {
        return permissionService.has(SecurityUtils.getCurrentUserOrNull(), Permission.REPORTS_FINANCIAL);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(Filters filters) {
        boolean money = canSeeMoney();
        List<Appointment> appts = appointments(filters);
        List<Payment> payments = money ? payments(filters) : List.of();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", filters.from());
        out.put("to", filters.to());
        out.put("financial", money);
        out.put("kpis", kpis(appts, payments, money));
        out.put("daily", daily(filters, appts, payments, money));
        out.put("byDoctor", groupBy(appts, payments, money,
                a -> a.getDoctor().getId(), a -> "Dr(a). " + a.getDoctor().getUser().getFirstName() + " " + a.getDoctor().getUser().getLastName(),
                p -> p.getAppointment().getDoctor().getId()));
        out.put("bySpecialty", groupBy(appts, payments, money,
                a -> a.getDoctor().getSpecialty().getId(), a -> a.getDoctor().getSpecialty().getName(),
                p -> p.getAppointment().getDoctor().getSpecialty().getId()));
        out.put("byBranch", groupBy(appts, payments, money,
                a -> a.getBranch().getId(), a -> a.getBranch().getName(),
                p -> p.getAppointment().getBranch().getId()));
        out.put("byMethod", money ? byMethod(payments) : List.of());
        out.put("byHour", byHour(appts));
        out.put("byWeekday", byWeekday(appts));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Appointment> appointments(Filters f) {
        var spec = AppointmentSpecifications.search(f.doctorId(), null, f.branchId(), f.specialtyId(), f.from(), f.to(), null, null);
        return appointmentRepository.findAll(spec);
    }

    @Transactional(readOnly = true)
    public List<Payment> payments(Filters f) {
        return paymentRepository.findForReport(f.from().atStartOfDay(), f.to().plusDays(1).atStartOfDay(), f.doctorId()).stream()
                .filter(p -> f.branchId() == null || f.branchId().equals(p.getAppointment().getBranch().getId()))
                .filter(p -> f.specialtyId() == null || f.specialtyId().equals(p.getAppointment().getDoctor().getSpecialty().getId()))
                .toList();
    }

    // ── Cálculos ────────────────────────────────────────────────

    private Map<String, Object> kpis(List<Appointment> appts, List<Payment> payments, boolean money) {
        long total = appts.size();
        long completed = count(appts, AppointmentStatus.COMPLETED);
        long cancelled = count(appts, AppointmentStatus.CANCELLED);
        long noShow = count(appts, AppointmentStatus.NO_SHOW);
        long scheduled = count(appts, AppointmentStatus.SCHEDULED);
        long online = appts.stream().filter(a -> a.getCreatedByUser() == null).count();
        Set<Long> patients = new HashSet<>();
        appts.forEach(a -> patients.add(a.getPatient().getId()));
        long newPatients = newPatients(appts);

        Map<String, Object> k = new LinkedHashMap<>();
        k.put("appointments", total);
        k.put("completed", completed);
        k.put("cancelled", cancelled);
        k.put("noShow", noShow);
        k.put("scheduled", scheduled);
        k.put("patients", patients.size());
        k.put("newPatients", newPatients);
        k.put("completionRate", rate(completed, completed + noShow));
        k.put("noShowRate", rate(noShow, completed + noShow));
        k.put("cancellationRate", rate(cancelled, total));
        k.put("onlineShare", rate(online, total));
        if (money) {
            BigDecimal revenue = sum(payments, PaymentStatus.COMPLETED);
            long paidCount = payments.stream().filter(p -> p.getStatus() == PaymentStatus.COMPLETED).count();
            k.put("revenue", revenue);
            k.put("refunded", sum(payments, PaymentStatus.REFUNDED));
            k.put("payments", paidCount);
            k.put("averageTicket", paidCount == 0 ? BigDecimal.ZERO : revenue.divide(BigDecimal.valueOf(paidCount), 2, RoundingMode.HALF_UP));
            BigDecimal pending = appts.stream()
                    .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED && a.getPaymentStatus() != null
                            && "UNPAID".equals(a.getPaymentStatus().name()) && a.getPrice() != null)
                    .map(Appointment::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
            k.put("pendingToCollect", pending);
        }
        return k;
    }

    /** Pacientes cuya primera cita en el hospital cae dentro del periodo. */
    private long newPatients(List<Appointment> appts) {
        if (appts.isEmpty()) return 0;
        Map<Long, LocalDate> firstInRange = new HashMap<>();
        for (Appointment a : appts) firstInRange.merge(a.getPatient().getId(), a.getAppointmentDate(), (x, y) -> x.isBefore(y) ? x : y);
        Map<Long, LocalDate> firstEver = new HashMap<>();
        for (Object[] row : appointmentRepository.firstAppointmentDates(firstInRange.keySet())) {
            firstEver.put((Long) row[0], (LocalDate) row[1]);
        }
        return firstInRange.entrySet().stream().filter(e -> e.getValue().equals(firstEver.get(e.getKey()))).count();
    }

    private List<Map<String, Object>> daily(Filters f, List<Appointment> appts, List<Payment> payments, boolean money) {
        Map<LocalDate, Map<String, Object>> days = new LinkedHashMap<>();
        for (LocalDate d = f.from(); !d.isAfter(f.to()); d = d.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d);
            row.put("completed", 0L);
            row.put("scheduled", 0L);
            row.put("cancelled", 0L);
            row.put("noShow", 0L);
            if (money) row.put("revenue", BigDecimal.ZERO);
            days.put(d, row);
        }
        for (Appointment a : appts) {
            Map<String, Object> row = days.get(a.getAppointmentDate());
            if (row == null) continue;
            String key = switch (a.getStatus()) {
                case COMPLETED -> "completed";
                case CANCELLED -> "cancelled";
                case NO_SHOW -> "noShow";
                case SCHEDULED -> "scheduled";
            };
            row.put(key, (Long) row.get(key) + 1);
        }
        if (money) {
            for (Payment p : payments) {
                if (p.getStatus() != PaymentStatus.COMPLETED) continue;
                Map<String, Object> row = days.get(p.getCreatedAt().toLocalDate());
                if (row != null) row.put("revenue", ((BigDecimal) row.get("revenue")).add(p.getAmount()));
            }
        }
        return new ArrayList<>(days.values());
    }

    private List<Map<String, Object>> groupBy(List<Appointment> appts, List<Payment> payments, boolean money,
                                              Function<Appointment, Long> key, Function<Appointment, String> name,
                                              Function<Payment, Long> paymentKey) {
        Map<Long, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Appointment a : appts) {
            Map<String, Object> g = groups.computeIfAbsent(key.apply(a), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", k);
                m.put("name", name.apply(a));
                m.put("appointments", 0L);
                m.put("completed", 0L);
                m.put("noShow", 0L);
                m.put("cancelled", 0L);
                if (money) m.put("revenue", BigDecimal.ZERO);
                return m;
            });
            g.put("appointments", (Long) g.get("appointments") + 1);
            switch (a.getStatus()) {
                case COMPLETED -> g.put("completed", (Long) g.get("completed") + 1);
                case NO_SHOW -> g.put("noShow", (Long) g.get("noShow") + 1);
                case CANCELLED -> g.put("cancelled", (Long) g.get("cancelled") + 1);
                default -> { }
            }
        }
        if (money) {
            for (Payment p : payments) {
                if (p.getStatus() != PaymentStatus.COMPLETED) continue;
                Map<String, Object> g = groups.get(paymentKey.apply(p));
                if (g != null) g.put("revenue", ((BigDecimal) g.get("revenue")).add(p.getAmount()));
            }
        }
        List<Map<String, Object>> list = new ArrayList<>(groups.values());
        list.forEach(g -> g.put("noShowRate", rate((Long) g.get("noShow"), (Long) g.get("completed") + (Long) g.get("noShow"))));
        list.sort((x, y) -> Long.compare((Long) y.get("appointments"), (Long) x.get("appointments")));
        return list;
    }

    private List<Map<String, Object>> byMethod(List<Payment> payments) {
        Map<String, BigDecimal> amount = new TreeMap<>();
        Map<String, Long> count = new TreeMap<>();
        for (Payment p : payments) {
            if (p.getStatus() != PaymentStatus.COMPLETED) continue;
            String m = p.getMethod().name();
            amount.merge(m, p.getAmount(), BigDecimal::add);
            count.merge(m, 1L, Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        amount.forEach((m, a) -> out.add(Map.of("method", m, "amount", a, "count", count.get(m))));
        out.sort((x, y) -> ((BigDecimal) y.get("amount")).compareTo((BigDecimal) x.get("amount")));
        return out;
    }

    private List<Map<String, Object>> byHour(List<Appointment> appts) {
        long[] counts = new long[24];
        appts.stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED)
                .forEach(a -> counts[a.getStartTime().getHour()]++);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            if (counts[h] > 0) out.add(Map.of("hour", h, "count", counts[h]));
        }
        return out;
    }

    private List<Map<String, Object>> byWeekday(List<Appointment> appts) {
        long[] counts = new long[7];
        appts.stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED)
                .forEach(a -> counts[a.getAppointmentDate().getDayOfWeek().getValue() - 1]++);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) out.add(Map.of("weekday", d.getValue(), "count", counts[d.getValue() - 1]));
        return out;
    }

    private static long count(List<Appointment> appts, AppointmentStatus status) {
        return appts.stream().filter(a -> a.getStatus() == status).count();
    }

    private static BigDecimal sum(List<Payment> payments, PaymentStatus status) {
        return payments.stream().filter(p -> p.getStatus() == status).map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Porcentaje con un decimal (0 si no hay base). */
    private static double rate(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0;
    }
}
