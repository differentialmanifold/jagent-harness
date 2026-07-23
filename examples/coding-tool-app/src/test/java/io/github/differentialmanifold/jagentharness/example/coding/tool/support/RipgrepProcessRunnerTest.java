package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.differentialmanifold.jagentharness.core.agent.StopRegistration;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RipgrepProcessRunnerTest {

    @TempDir
    Path workingDirectory;

    @Test
    void drainsStdoutAndStderrWithoutBlocking() throws Exception {
        RipgrepProcessRunner.Result<String> result = new RipgrepProcessRunner().run(
                javaExecutable(),
                helperArguments("streams"),
                workingDirectory,
                StopSignal.none(),
                (stdout, terminateForLimit) -> readAll(stdout));

        assertEquals(0, result.getExitCode());
        assertTrue(result.getOutput().contains("stdout-9999"));
        assertTrue(result.getStderr().contains("stderr-0"));
        assertTrue(result.getStderr().getBytes(StandardCharsets.UTF_8).length <= 64 * 1024);
    }

    @Test
    void terminatesTheProcessWhenParserReachesItsLimit() throws Exception {
        RipgrepProcessRunner.Result<String> result = new RipgrepProcessRunner().run(
                javaExecutable(),
                helperArguments("sleep"),
                workingDirectory,
                StopSignal.none(),
                (stdout, terminateForLimit) -> {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stdout, StandardCharsets.UTF_8));
                    String line = reader.readLine();
                    terminateForLimit.run();
                    return line;
                });

        assertEquals("ready", result.getOutput());
        assertTrue(result.isTerminatedForLimit());
    }

    @Test
    void reportsTimeoutWithoutFallingBack() {
        RipgrepProcessRunner runner = new RipgrepProcessRunner(1);

        RipgrepProcessRunner.RipgrepExecutionException error = assertThrows(
                RipgrepProcessRunner.RipgrepExecutionException.class,
                () -> runner.run(
                        javaExecutable(),
                        helperArguments("sleep"),
                        workingDirectory,
                        StopSignal.none(),
                        (stdout, terminateForLimit) -> readAll(stdout)));

        assertTrue(error.getMessage().contains("timed out"));
    }

    @Test
    void stopSignalKillsTheProcessAndThrowsStopRequested() throws Exception {
        RipgrepProcessRunner runner = new RipgrepProcessRunner(10);
        TestStopSignal stopSignal = new TestStopSignal();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(() -> {
                try {
                    runner.run(
                            javaExecutable(),
                            helperArguments("sleep"),
                            workingDirectory,
                            stopSignal,
                            (stdout, terminateForLimit) -> readAll(stdout));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(200L);
            stopSignal.requestStop();
            ExecutionException error = assertThrows(ExecutionException.class, running::get);
            assertTrue(error.getCause().getCause() instanceof StopRequestedException);
        } finally {
            executor.shutdownNow();
        }
    }

    private Path javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().startsWith("windows")
                ? "java.exe"
                : "java";
        return Paths.get(System.getProperty("java.home"), "bin", executable);
    }

    private List<String> helperArguments(String mode) {
        return Arrays.asList(
                "-cp",
                System.getProperty("java.class.path"),
                TestProcess.class.getName(),
                mode);
    }

    private static String readAll(InputStream inputStream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    public static class TestProcess {

        public static void main(String[] args) throws Exception {
            String mode = args.length == 0 ? "" : args[0];
            if ("streams".equals(mode)) {
                for (int i = 0; i < 10000; i++) {
                    System.out.println("stdout-" + i);
                    System.err.println("stderr-" + i);
                }
                return;
            }
            System.out.println("ready");
            System.out.flush();
            Thread.sleep(10000L);
        }
    }

    private static class TestStopSignal implements StopSignal {

        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private volatile Runnable listener;

        private void requestStop() {
            stopped.set(true);
            Runnable current = listener;
            if (current != null) {
                current.run();
            }
        }

        @Override
        public boolean isAborted() {
            return stopped.get();
        }

        @Override
        public void throwIfAborted() {
            if (isAborted()) {
                throw new StopRequestedException();
            }
        }

        @Override
        public StopRegistration onStop(Runnable action) {
            listener = action;
            if (isAborted()) {
                action.run();
            }
            return () -> {
                if (listener == action) {
                    listener = null;
                }
            };
        }
    }
}
