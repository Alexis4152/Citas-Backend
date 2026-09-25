package com.hospital.citas.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Hilo dedicado para el envío de notificaciones (correos con SMTP real, que puede tardar
 * segundos por destinatario) -- antes de esto, agendar/cancelar/reprogramar una cita
 * bloqueaba la respuesta HTTP hasta terminar de mandar TODOS los correos (paciente, doctor,
 * cada recepcionista, cada admin) uno por uno. Con {@link EmailService} anotado
 * {@code @Async("notificationExecutor")}, esos envíos corren en este pool y la respuesta al
 * usuario ya no espera por ellos.
 * <p>
 * También habilita {@code @Scheduled} (usado por
 * {@code AppointmentServiceImpl.autoMarkPastDueAsNoShow}, la tarea que marca "no asistió"
 * las citas cuyo horario ya terminó sin que nadie las haya marcado a tiempo).
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("notif-");
        executor.initialize();
        return executor;
    }
}
