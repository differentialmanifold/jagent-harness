package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.agent.StopRegistration;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashToolTest {

    @TempDir
    Path tempDir;

    @Test
    void usesGitBashOnWindows() throws Exception {
        Path gitBin = tempDir.resolve("Git").resolve("bin");
        Files.createDirectories(gitBin);
        Path gitBash = gitBin.resolve("bash.exe");
        Files.write(gitBash, new byte[0]);

        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", gitBin.toString());

        assertEquals(Arrays.asList(gitBash.toString(), "-lc", "pwd"),
                BashTool.shellCommand("pwd", "Windows 11", environment));
    }

    @Test
    void requiresGitBashOnWindows() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> BashTool.shellCommand("dir", "Windows 11", Collections.emptyMap()));

        assertTrue(exception.getMessage().contains("Git Bash is required"));
    }

    @Test
    void usesShOnUnixLikeSystems() {
        assertEquals(Arrays.asList("/bin/sh", "-lc", "ls"),
                BashTool.shellCommand("ls", "Mac OS X", Collections.emptyMap()));
    }

    @Test
    void stopsRunningProcessWhenSignalIsAborted() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        ObjectMapper objectMapper = new ObjectMapper();
        TestStopSignal control = new TestStopSignal();
        ToolContext context = new ToolContext(
                "session",
                "turn",
                null,
                tempDir,
                null,
                Collections.emptyMap(),
                control);
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("command", "exec sleep 30");
        arguments.put("timeoutSeconds", 60);
        BashTool tool = new BashTool(objectMapper, new WorkspacePathResolver());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> execution = executor.submit(() -> {
                try {
                    tool.execute(context, arguments);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(200);
            control.requestStop();

            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> execution.get(3, TimeUnit.SECONDS));
            assertTrue(exception.getCause().getCause() instanceof StopRequestedException);
        } finally {
            executor.shutdownNow();
        }
    }

    private static class TestStopSignal implements StopSignal {

        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private volatile Runnable listener;

        private void requestStop() {
            stopped.set(true);
            Runnable action = listener;
            if (action != null) {
                action.run();
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
