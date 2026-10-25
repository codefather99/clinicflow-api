package com.clinicflow.web.sse;

import com.clinicflow.domain.port.AppointmentQueryPort;
import com.clinicflow.domain.port.DomainException;
import com.clinicflow.domain.port.PatientView;
import com.clinicflow.domain.port.QueueChanged;
import com.clinicflow.web.dto.Mappers;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Live position for ONE patient's own booking (design.md section 7). In-memory registry: single instance only.
 * Listens for QueueChanged (published by the domain after commit) and pushes a fresh PatientView to every
 * connected patient affected. Keep-alive comments stop proxies closing idle streams.
 */
@Component
public class SseBroadcaster {
    private static final Logger log = LoggerFactory.getLogger(SseBroadcaster.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, List<SseEmitter>> byReference = new ConcurrentHashMap<>();
    private final AppointmentQueryPort queries;
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-keepalive");
        t.setDaemon(true);
        return t;
    });

    public SseBroadcaster(AppointmentQueryPort queries) { this.queries = queries; }

    @PostConstruct
    void start() { keepAlive.scheduleAtFixedRate(this::ping, 25, 25, TimeUnit.SECONDS); }

    @PreDestroy
    void stop() { keepAlive.shutdownNow(); }

    /** @throws com.clinicflow.domain.port.NotFoundException (-> 404) before any stream is opened */
    public SseEmitter subscribe(String reference) {
        PatientView current = queries.lookup(reference);
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        byReference.computeIfAbsent(reference, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> remove(reference, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());
        send(reference, emitter, current);
        return emitter;
    }

    /** fallbackExecution = true so it also fires when the fake ports publish outside a transaction. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onQueueChanged(QueueChanged event) {
        for (String reference : event.references()) {
            List<SseEmitter> emitters = byReference.get(reference);
            if (emitters == null || emitters.isEmpty()) continue;
            try {
                PatientView view = queries.lookup(reference);
                for (SseEmitter e : emitters) send(reference, e, view);
            } catch (DomainException ignored) {
                // booking vanished (demo reset): nothing to push
            }
        }
    }

    private void send(String reference, SseEmitter emitter, PatientView view) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().name("appointment").data(Mappers.patient(view), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                remove(reference, emitter);
            }
        }
    }

    private void ping() {
        byReference.forEach((reference, emitters) -> {
            for (SseEmitter e : emitters) {
                synchronized (e) {
                    try {
                        e.send(SseEmitter.event().comment("keep-alive"));
                    } catch (IOException | IllegalStateException ex) {
                        remove(reference, e);
                    }
                }
            }
        });
    }

    private void remove(String reference, SseEmitter emitter) {
        List<SseEmitter> list = byReference.get(reference);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) byReference.remove(reference, list);
        }
    }
}
