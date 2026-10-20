package com.hospital.citas.service;

import com.hospital.citas.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Avisos en tiempo real de cambios en la agenda de un doctor (Server-Sent Events): cuando
 * alguien aparta, suelta, agenda, cancela o reprograma un horario, cada navegador que está
 * viendo los horarios de ese doctor recibe "changed" y vuelve a pedir la disponibilidad. El
 * aviso no trae datos (nada personal viaja por aquí): solo dice "algo cambió".
 * <p>
 * Las suscripciones viven en la memoria de esta instancia. Con varias instancias, el aviso
 * llega solo a quienes estén conectados a la misma; los demás se enteran con el refresco
 * periódico que el frontend mantiene como respaldo.
 */
@Component
public class AvailabilityEvents {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityEvents.class);
    private static final long TIMEOUT_MS = 30 * 60_000L;
    /** Tope por doctor: evita que alguien abra miles de conexiones para agotar la memoria. */
    private static final int MAX_PER_DOCTOR = 500;

    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long hospitalId, long doctorId) {
        String key = key(hospitalId, doctorId);
        Set<SseEmitter> set = subscribers.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>());
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        if (set.size() >= MAX_PER_DOCTOR) {
            emitter.complete();
            return emitter;
        }
        set.add(emitter);
        Runnable remove = () -> set.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data("ok").reconnectTime(5000));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    /** Avisa del cambio cuando la transacción actual confirme (si no hay, de inmediato). */
    public void publishAfterCommit(Long doctorId) {
        Long hospitalId = TenantContext.hospitalIdOrNull();
        if (hospitalId == null || doctorId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(hospitalId, doctorId);
                }
            });
        } else {
            publish(hospitalId, doctorId);
        }
    }

    public void publish(long hospitalId, long doctorId) {
        Set<SseEmitter> set = subscribers.get(key(hospitalId, doctorId));
        if (set == null || set.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name("changed").data(String.valueOf(doctorId)));
            } catch (Exception e) {
                set.remove(emitter);
            }
        }
    }

    /** Latido cada 25 s: mantiene viva la conexión a través de proxies que cortan las inactivas. */
    @Scheduled(fixedRate = 25_000)
    void heartbeat() {
        subscribers.forEach((key, set) -> {
            for (SseEmitter emitter : set) {
                try {
                    emitter.send(SseEmitter.event().comment("latido"));
                } catch (Exception e) {
                    set.remove(emitter);
                }
            }
        });
        subscribers.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public int connections() {
        return subscribers.values().stream().mapToInt(Set::size).sum();
    }

    private static String key(long hospitalId, long doctorId) {
        return hospitalId + ":" + doctorId;
    }
}
