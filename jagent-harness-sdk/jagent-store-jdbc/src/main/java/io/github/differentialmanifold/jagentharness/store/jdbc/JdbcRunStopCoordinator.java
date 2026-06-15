package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_STOP_REQUESTED = "STOP_REQUESTED";

    private final JdbcTemplate jdbcTemplate;
    private final long pollIntervalMillis;
    private final long leaseDurationMillis;
    private final long heartbeatIntervalMillis;
    private final String instanceId;
    private final ScheduledExecutorService listenerExecutor;

    public JdbcRunStopCoordinator(JdbcTemplate jdbcTemplate, JdbcRunStopProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.pollIntervalMillis = positive(properties.getPollIntervalMillis(), "pollIntervalMillis");
        this.leaseDurationMillis = positive(properties.getLeaseDurationMillis(), "leaseDurationMillis");
        if (leaseDurationMillis <= pollIntervalMillis) {
            throw new IllegalArgumentException("leaseDurationMillis must be greater than pollIntervalMillis");
        }
        this.heartbeatIntervalMillis = Math.max(pollIntervalMillis, leaseDurationMillis / 3L);
        int listenerThreads = positive(properties.getListenerThreads(), "listenerThreads");
        this.instanceId = UUID.randomUUID().toString();
        this.listenerExecutor = Executors.newScheduledThreadPool(
                listenerThreads,
                new ListenerThreadFactory());
    }

    @Override
    public RunStopHandle register(String requestId, String sessionId) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "delete from agent_runs where request_id = ? and lease_until < ?",
                requestId,
                now);
        try {
            jdbcTemplate.update(
                    "insert into agent_runs "
                            + "(request_id, session_id, owner_instance_id, status, lease_until, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?)",
                    requestId,
                    sessionId,
                    instanceId,
                    STATUS_RUNNING,
                    now + leaseDurationMillis,
                    now,
                    now);
        } catch (DataAccessException e) {
            if (findRun(requestId) != null) {
                throw new ActiveRunException(requestId);
            }
            throw e;
        }

        JdbcRunStopHandle handle = new JdbcRunStopHandle(requestId, sessionId, now);
        try {
            handle.start();
            return handle;
        } catch (RuntimeException e) {
            removeOwnedRun(requestId);
            throw e;
        }
    }

    @Override
    public StopRequestResult requestStop(String requestId) {
        long now = System.currentTimeMillis();
        int updated = jdbcTemplate.update(
                "update agent_runs set status = ?, updated_at = ? "
                        + "where request_id = ? and status = ? and lease_until >= ?",
                STATUS_STOP_REQUESTED,
                now,
                requestId,
                STATUS_RUNNING,
                now);
        if (updated > 0) {
            return StopRequestResult.REQUESTED;
        }

        RunRow row = findRun(requestId);
        if (row == null || row.leaseUntil < now) {
            return StopRequestResult.NOT_FOUND;
        }
        return STATUS_STOP_REQUESTED.equals(row.status)
                ? StopRequestResult.ALREADY_REQUESTED
                : StopRequestResult.NOT_FOUND;
    }

    @Override
    public void close() {
        listenerExecutor.shutdownNow();
    }

    private RunRow findRun(String requestId) {
        List<RunRow> rows = jdbcTemplate.query(
                "select owner_instance_id, status, lease_until from agent_runs where request_id = ?",
                (resultSet, rowNum) -> new RunRow(
                        resultSet.getString("owner_instance_id"),
                        resultSet.getString("status"),
                        resultSet.getLong("lease_until")),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void removeOwnedRun(String requestId) {
        try {
            jdbcTemplate.update(
                    "delete from agent_runs where request_id = ? and owner_instance_id = ?",
                    requestId,
                    instanceId);
        } catch (RuntimeException ignored) {
            // The lease allows another instance to reclaim the request after a transient cleanup failure.
        }
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
        private volatile long lastDatabaseContact;
        private volatile long nextHeartbeatAt;
        private volatile ScheduledFuture<?> listenerTask;

        private JdbcRunStopHandle(String requestId, String sessionId, long registeredAt) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.lastDatabaseContact = registeredAt;
            this.nextHeartbeatAt = registeredAt + heartbeatIntervalMillis;
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
            removeOwnedRun(requestId);
        }

        private void refresh() {
            if (closed.get()) {
                return;
            }
            long now = System.currentTimeMillis();
            try {
                RunRow row = findRun(requestId);
                if (row == null || !instanceId.equals(row.ownerInstanceId)) {
                    requestLocalStop();
                    return;
                }
                lastDatabaseContact = now;
                if (row.leaseUntil < now) {
                    requestLocalStop();
                    return;
                }
                if (STATUS_STOP_REQUESTED.equals(row.status)) {
                    requestLocalStop();
                } else if (!STATUS_RUNNING.equals(row.status)) {
                    requestLocalStop();
                    return;
                }
                if (now < nextHeartbeatAt) {
                    return;
                }
                int renewed = jdbcTemplate.update(
                        "update agent_runs set lease_until = ?, updated_at = ? "
                                + "where request_id = ? and owner_instance_id = ? and status in (?, ?)",
                        now + leaseDurationMillis,
                        now,
                        requestId,
                        instanceId,
                        STATUS_RUNNING,
                        STATUS_STOP_REQUESTED);
                if (renewed > 0) {
                    nextHeartbeatAt = now + heartbeatIntervalMillis;
                    return;
                }
                RunRow current = findRun(requestId);
                if (current == null
                        || !instanceId.equals(current.ownerInstanceId)
                        || (!STATUS_RUNNING.equals(current.status)
                        && !STATUS_STOP_REQUESTED.equals(current.status))) {
                    requestLocalStop();
                }
            } catch (RuntimeException e) {
                if (now - lastDatabaseContact >= leaseDurationMillis) {
                    requestLocalStop();
                }
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

    private static class RunRow {
        private final String ownerInstanceId;
        private final String status;
        private final long leaseUntil;

        private RunRow(String ownerInstanceId, String status, long leaseUntil) {
            this.ownerInstanceId = ownerInstanceId;
            this.status = status;
            this.leaseUntil = leaseUntil;
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
