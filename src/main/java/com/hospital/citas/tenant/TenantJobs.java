package com.hospital.citas.tenant;

import com.hospital.citas.entity.Hospital;
import com.hospital.citas.repository.HospitalRepository;
import com.hospital.citas.service.impl.AppointmentServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Tareas programadas de citas, corridas por separado para cada hospital activo: cada vuelta
 * abre su propia transacción con ese hospital en {@link TenantContext}, así los correos salen
 * con el SMTP, el nombre y los enlaces de ese hospital. Un error en uno no frena a los demás.
 */
@Component
@RequiredArgsConstructor
public class TenantJobs {

    private static final Logger log = LoggerFactory.getLogger(TenantJobs.class);

    private final HospitalRepository hospitalRepository;
    private final AppointmentServiceImpl appointmentService;

    /** Cada minuto: cierra como "no asistió" / "atendida" las citas que nadie marcó a tiempo. */
    @Scheduled(fixedRate = 60_000)
    public void autoMarkPastDue() {
        forEachHospital("marcar citas vencidas", h -> appointmentService.autoMarkPastDueAsNoShow());
    }

    /** Cada 5 minutos: recordatorio por correo 2 horas antes de la cita. */
    @Scheduled(fixedRate = 300_000)
    public void twoHourReminders() {
        forEachHospital("recordatorios de 2 horas", h -> appointmentService.sendTwoHourReminders());
    }

    private void forEachHospital(String jobName, Consumer<Hospital> job) {
        List<Hospital> hospitals = TenantContext.callAs(TenantContext.ROOT, hospitalRepository::findByIsActiveTrueOrderByIdAsc);
        for (Hospital hospital : hospitals) {
            try {
                TenantContext.runAs(hospital.getId(), () -> job.accept(hospital));
            } catch (Exception e) {
                log.error("Falló la tarea '{}' del hospital {} ({})", jobName, hospital.getId(), hospital.getSlug(), e);
            }
        }
    }
}
