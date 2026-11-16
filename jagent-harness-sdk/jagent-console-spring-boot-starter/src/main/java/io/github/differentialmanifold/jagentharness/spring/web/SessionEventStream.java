package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.event.*;
import io.github.differentialmanifold.jagentharness.core.execution.ProtocolMapper;
import io.github.differentialmanifold.jagentharness.core.identity.*;
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
    private final Map<SseEmitter, Observer> observers = new LinkedHashMap<>();

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
        observers.put(emitter, new Observer(session));
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
        for (Map.Entry<SseEmitter, Observer> entry : new ArrayList<>(observers.entrySet()))
            if (Objects.equals(entry.getValue().session, event.getSessionId())
                    && entry.getValue().user.equals(UserContext.current())
                    && entry.getValue().valid.getAsBoolean())
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
            Observer observer = observers.get(emitter);
            try (UserContext ignored = UserContext.enter(observer.user)) {
                if (!observer.valid.getAsBoolean()) {
                    remove(emitter);
                    emitter.complete();
                    continue;
                }
                try {
                    sessions.requireSession(observer.session);
                } catch (SessionNotFoundException missing) {
                    send(
                            emitter,
                            "session_missing",
                            Collections.singletonMap("sessionId", observer.session));
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

    private static final class Observer {
        final String user = UserContext.current();
        final String session;
        final java.util.function.BooleanSupplier valid;

        Observer(String session) {
            this.session = session;
            org.springframework.web.context.request.RequestAttributes attrs =
                    org.springframework.web.context.request.RequestContextHolder
                            .getRequestAttributes();
            Object check =
                    attrs == null
                            ? null
                            : attrs.getAttribute(
                                    UserAccessService.ATTRIBUTE,
                                    org.springframework.web.context.request.RequestAttributes
                                            .SCOPE_REQUEST);
            valid = check == null ? () -> true : (java.util.function.BooleanSupplier) check;
        }
    }
}
