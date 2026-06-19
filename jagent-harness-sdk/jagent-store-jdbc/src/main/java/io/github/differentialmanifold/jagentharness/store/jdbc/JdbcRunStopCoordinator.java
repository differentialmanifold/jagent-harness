package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.differentialmanifold.jagentharness.core.agent.ActiveRunException;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopHandle;
import io.github.differentialmanifold.jagentharness.core.agent.StopRegistration;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestResult;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcRunStopCoordinator implements RunStopCoordinator, AutoCloseable {

    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_STOP_REQUESTED = "STOP_REQUESTED";

    private final JdbcTemplate jdbcTemplate;
    private final String applicationId;
    private final long pollIntervalMillis;
    private final ScheduledExecutorService listenerExecutor;

    public JdbcRunStopCoordinator(JdbcTemplate jdbcTemplate,
                                  JdbcStoreProperties storeProperties,
                                  JdbcRunStopProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.applicationId = storeProperties.requireApplicationId();
        this.pollIntervalMillis = positive(properties.getPollIntervalMillis(), "pollIntervalMillis");
        int listenerThreads = positive(properties.getListenerThreads(), "listenerThreads");
        this.listenerExecutor = Executors.newScheduledThreadPool(
                listenerThreads,
                new ListenerThreadFactory());
    }

    @Override
    public RunStopHandle register(String requestId, String sessionId) {
        long now = System.currentTimeMillis();
        try {
            jdbcTemplate.update(
                    "insert into agent_runs "
                            + "(application_id, request_id, session_id, status, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?)",
                    applicationId,
                    requestId,
                    sessionId,
                    STATUS_NORMAL,
                    now,
                    now);
        } catch (DataAccessException e) {
            if (findStatus(requestId) != null) {
                throw new ActiveRunException(requestId);
            }
            throw e;
        }

        JdbcRunStopHandle handle = new JdbcRunStopHandle(requestId, sessionId);
        handle.start();
        return handle;
    }

    @Override
    public StopRequestResult requestStop(String requestId) {
        long now = System.currentTimeMillis();
        int updated = jdbcTemplate.update(
                "update agent_runs set status = ?, updated_at = ? "
                        + "where application_id = ? and request_id = ? and status = ?",
                STATUS_STOP_REQUESTED,
                now,
                applicationId,
                requestId,
                STATUS_NORMAL);
        if (updated > 0) {
            return StopRequestResult.REQUESTED;
        }

        String status = findStatus(requestId);
        if (status == null) {
            return StopRequestResult.NOT_FOUND;
        }
        return STATUS_STOP_REQUESTED.equals(status)
                ? StopRequestResult.ALREADY_REQUESTED
                : StopRequestResult.NOT_FOUND;
    }

    @Override
    public void close() {
        listenerExecutor.shutdownNow();
    }

    private String findStatus(String requestId) {
        List<String> rows = jdbcTemplate.query(
                "select status from agent_runs where application_id = ? and request_id = ?",
                (resultSet, rowNum) -> resultSet.getString("status"),
                applicationId,
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private class JdbcRunStopHandle implements RunStopHandle {

        private final String requestId;
        private final String sessionId;
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Object listenersMonitor = new Object();
        private final List<Runnable> listeners = new ArrayList<Runnable>();
        private volatile ScheduledFuture<?> listenerTask;

        private JdbcRunStopHandle(String requestId, String sessionId) {
            this.requestId = requestId;
            this.sessionId = sessionId;
        }

        private void start() {
            listenerTask = listenerExecutor.scheduleWithFixedDelay(
                    this::refresh,
                    0L,
                    pollIntervalMillis,
                    TimeUnit.MILLISECONDS);
        }

        @Override
        public String getRequestId() {
            return requestId;
        }

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public boolean isAborted() {
            return aborted.get();
        }

        @Override
        public void throwIfAborted() {
            if (isAborted()) {
                throw new StopRequestedException();
            }
        }

        @Override
        public StopRegistration onStop(Runnable action) {
            if (action == null) {
                return () -> {
                };
            }
            synchronized (listenersMonitor) {
                if (!isAborted() && !closed.get()) {
                    listeners.add(action);
                    return () -> removeListener(action);
                }
            }
            if (isAborted()) {
                runQuietly(action);
            }
            return () -> {
            };
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> task = listenerTask;
            if (task != null) {
                task.cancel(false);
            }
            synchronized (listenersMonitor) {
                listeners.clear();
            }
        }

        private void refresh() {
            if (closed.get()) {
                return;
            }
            try {
                String status = findStatus(requestId);
                if (STATUS_STOP_REQUESTED.equals(status)) {
                    requestLocalStop();
                }
            } catch (RuntimeException ignored) {
                // Retry this request's status lookup on the next polling interval.
            }
        }

        private void requestLocalStop() {
            if (!aborted.compareAndSet(false, true)) {
                return;
            }
            List<Runnable> callbacks;
            synchronized (listenersMonitor) {
                callbacks = new ArrayList<Runnable>(listeners);
                listeners.clear();
            }
            for (Runnable callback : callbacks) {
                runQuietly(callback);
            }
        }

        private void removeListener(Runnable action) {
            synchronized (listenersMonitor) {
                listeners.remove(action);
            }
        }

        private void runQuietly(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static class ListenerThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jagent-stop-listener-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
