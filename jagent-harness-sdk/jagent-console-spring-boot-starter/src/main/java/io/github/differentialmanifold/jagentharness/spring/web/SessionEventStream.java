package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.event.*;
import io.github.differentialmanifold.jagentharness.core.execution.ProtocolMapper;
import io.github.differentialmanifold.jagentharness.core.session.*;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Read-only observers for work started by a user, personal Agent, or Runner. */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionEventStream implements AgentEventListener {
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread thread = new Thread(r, "session-observers");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final SessionManager sessions;
    private final ObjectMapper json;
    private final Map<SseEmitter, String> observers = new LinkedHashMap<>();

    public SessionEventStream(SessionManager sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
        heartbeat.scheduleWithFixedDelay(this::keepAlive, 15, 15, TimeUnit.SECONDS);
    }

    @GetMapping(value = "/{session}/events", produces = "text/event-stream")
    public synchronized SseEmitter watch(@PathVariable String session) {
        SseEmitter emitter = new SseEmitter(0L);
        SessionDetails details;
        try {
            details = sessions.getDetails(session);
        } catch (SessionNotFoundException missing) {
            // A browser may reconnect with an ID from before a database reset or deletion.
            // A terminal event lets it stop retrying without treating this as a server failure.
            send(emitter, "session_missing", Collections.singletonMap("sessionId", session));
            emitter.complete();
            return emitter;
        }
        observers.put(emitter, session);
        emitter.onCompletion(() -> remove(emitter));
        emitter.onTimeout(
                () -> {
                    remove(emitter);
                    emitter.complete();
                });
        emitter.onError(error -> remove(emitter));
        List<Object> events = new ArrayList<>();
        for (AgentEvent event : details.getEvents()) events.add(ProtocolMapper.event(event, json));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("session", details.getSession());
        snapshot.put("events", events);
        send(emitter, "snapshot", snapshot);
        return emitter;
    }

    @Override
    public synchronized void onEvent(AgentEvent event) {
        for (Map.Entry<SseEmitter, String> entry : new ArrayList<>(observers.entrySet()))
            if (Objects.equals(entry.getValue(), event.getSessionId()))
                send(entry.getKey(), "event", ProtocolMapper.event(event, json));
    }

    private synchronized void remove(SseEmitter emitter) {
        observers.remove(emitter);
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception error) {
            remove(emitter);
            emitter.completeWithError(error);
        }
    }

    private synchronized void keepAlive() {
        for (SseEmitter emitter : new ArrayList<>(observers.keySet())) {
            try {
                try {
                    sessions.requireSession(observers.get(emitter));
                } catch (SessionNotFoundException missing) {
                    send(
                            emitter,
                            "session_missing",
                            Collections.singletonMap("sessionId", observers.get(emitter)));
                    remove(emitter);
                    emitter.complete();
                    continue;
                }
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception error) {
                remove(emitter);
                emitter.completeWithError(error);
            }
        }
    }

    @PreDestroy
    public synchronized void close() {
        heartbeat.shutdownNow();
        for (SseEmitter emitter : new ArrayList<>(observers.keySet())) emitter.complete();
        observers.clear();
    }
}
