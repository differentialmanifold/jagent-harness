package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.differentialmanifold.jagentharness.core.agent.StopRegistration;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;

public class RipgrepProcessRunner {

    private static final int STDERR_LIMIT_BYTES = 64 * 1024;
    private static final int STREAM_COMPLETION_TIMEOUT_SECONDS = 2;

    private final int timeoutSeconds;

    public RipgrepProcessRunner() {
        this(30);
    }

    RipgrepProcessRunner(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public <T> Result<T> run(Path executable,
                             List<String> arguments,
                             Path workingDirectory,
                             StopSignal stopSignal,
                             OutputParser<T> outputParser) throws Exception {
        stopSignal.throwIfAborted();
        List<String> command = new ArrayList<String>(arguments.size() + 1);
        command.add(executable.toString());
        command.addAll(arguments);

        final Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workingDirectory.toFile());
            process = processBuilder.start();
        } catch (IOException e) {
            throw new ProcessStartException("Failed to start ripgrep: " + e.getMessage(), e);
        }

        closeQuietly(process.getOutputStream());
        AtomicBoolean terminatedForLimit = new AtomicBoolean(false);
        Runnable terminateForLimit = () -> {
            terminatedForLimit.set(true);
            destroyProcess(process);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2, new DaemonThreadFactory());
        Future<T> stdout = executor.submit(new Callable<T>() {
            @Override
            public T call() throws Exception {
                try {
                    return outputParser.parse(process.getInputStream(), terminateForLimit);
                } catch (Exception e) {
                    destroyProcess(process);
                    throw e;
                }
            }
        });
        Future<String> stderr = executor.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return readBoundedAndDrain(process.getErrorStream(), STDERR_LIMIT_BYTES);
            }
        });

        try (StopRegistration ignored = stopSignal.onStop(() -> destroyProcess(process))) {
            boolean finished;
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                destroyProcess(process);
                Thread.currentThread().interrupt();
                if (stopSignal.isAborted()) {
                    throw new StopRequestedException(e);
                }
                throw e;
            }

            if (!finished) {
                destroyProcess(process);
                throw new RipgrepExecutionException("ripgrep timed out after " + timeoutSeconds + " seconds");
            }
            stopSignal.throwIfAborted();

            T parsedOutput = futureValue(stdout, "stdout parser");
            String errorOutput = futureValue(stderr, "stderr reader");
            return new Result<T>(
                    parsedOutput,
                    errorOutput,
                    process.exitValue(),
                    terminatedForLimit.get());
        } finally {
            destroyProcess(process);
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            stdout.cancel(true);
            stderr.cancel(true);
            executor.shutdownNow();
        }
    }

    private <T> T futureValue(Future<T> future, String description) throws Exception {
        try {
            return future.get(STREAM_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException e) {
            throw new RipgrepExecutionException("Timed out waiting for ripgrep " + description, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RipgrepExecutionException("Failed to process ripgrep " + description, cause);
        }
    }

    private static String readBoundedAndDrain(InputStream inputStream, int limitBytes) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(limitBytes, 8192));
        byte[] buffer = new byte[8192];
        int read;
        int remaining = limitBytes;
        while ((read = inputStream.read(buffer)) >= 0) {
            if (remaining > 0) {
                int copy = Math.min(read, remaining);
                captured.write(buffer, 0, copy);
                remaining -= copy;
            }
        }
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void destroyProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(100, TimeUnit.MILLISECONDS) && process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    public interface OutputParser<T> {

        T parse(InputStream stdout, Runnable terminateForLimit) throws Exception;
    }

    public static class Result<T> {

        private final T output;
        private final String stderr;
        private final int exitCode;
        private final boolean terminatedForLimit;

        Result(T output, String stderr, int exitCode, boolean terminatedForLimit) {
            this.output = output;
            this.stderr = stderr;
            this.exitCode = exitCode;
            this.terminatedForLimit = terminatedForLimit;
        }

        public T getOutput() {
            return output;
        }

        public String getStderr() {
            return stderr;
        }

        public int getExitCode() {
            return exitCode;
        }

        public boolean isTerminatedForLimit() {
            return terminatedForLimit;
        }
    }

    public static class ProcessStartException extends IOException {

        ProcessStartException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class RipgrepExecutionException extends IOException {

        RipgrepExecutionException(String message) {
            super(message);
        }

        RipgrepExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class DaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jagent-ripgrep-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
