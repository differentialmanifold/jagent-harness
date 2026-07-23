package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RipgrepSearchEngineTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void grepUsesLiteralMatchingAndDoesNotApplyIgnoreFiles() throws Exception {
        RipgrepSearchEngine engine = availableEngine();
        write(".gitignore", "ignored.txt\n");
        write("ignored.txt", "a+b\nab\n");

        Optional<RipgrepSearchEngine.GrepResult> result = engine.grep(
                workspaceRoot,
                "a+b",
                "**/*",
                true,
                10,
                StopSignal.none());

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getMatches().size());
        assertEquals(
                workspaceRoot.resolve("ignored.txt"),
                result.get().getMatches().get(0).getPath());
        assertEquals("a+b", result.get().getMatches().get(0).getPreview());
        assertFalse(result.get().isTruncated());
    }

    @Test
    void grepReadsOneExtraMatchToReportTruncation() throws Exception {
        RipgrepSearchEngine engine = availableEngine();
        write("one.txt", "needle\n");
        write("two.txt", "needle\n");

        Optional<RipgrepSearchEngine.GrepResult> result = engine.grep(
                workspaceRoot,
                "needle",
                "**/*",
                true,
                1,
                StopSignal.none());

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getMatches().size());
        assertTrue(result.get().isTruncated());
    }

    @Test
    void grepHandlesLongLinesAndReportsOneResultPerMatchingLine() throws Exception {
        RipgrepSearchEngine engine = availableEngine();
        Path path = workspaceRoot.resolve("long.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("needle needle ");
            char[] chunk = new char[8192];
            Arrays.fill(chunk, 'x');
            int total = 9 * 1024 * 1024;
            for (int written = 0; written < total; written += chunk.length) {
                writer.write(chunk, 0, Math.min(chunk.length, total - written));
            }
            writer.newLine();
        }

        Optional<RipgrepSearchEngine.GrepResult> result = engine.grep(
                workspaceRoot,
                "needle",
                "**/*",
                true,
                10,
                StopSignal.none());

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getMatches().size());
        assertEquals(path, result.get().getMatches().get(0).getPath());
        String preview = result.get().getMatches().get(0).getPreview();
        assertEquals(2003, preview.length());
        assertTrue(preview.startsWith("needle needle "));
        assertTrue(preview.endsWith("..."));
    }

    @Test
    void grepUsesNullDelimitedPaths() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().startsWith("windows"));
        RipgrepSearchEngine engine = availableEngine();
        write("line\nbreak.txt", "needle\n");

        Optional<RipgrepSearchEngine.GrepResult> result = engine.grep(
                workspaceRoot,
                "needle",
                "**/*",
                true,
                10,
                StopSignal.none());

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getMatches().size());
        assertEquals(
                workspaceRoot.resolve("line\nbreak.txt"),
                result.get().getMatches().get(0).getPath());
    }

    @Test
    void findUsesNullDelimitedPathsAndExistingFilters() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().startsWith("windows"));
        RipgrepSearchEngine engine = availableEngine();
        write("src/line\nbreak.java", "content\n");
        write("src/other.txt", "content\n");
        write("target/generated.java", "content\n");
        PathMatcher javaName = FileSystems.getDefault().getPathMatcher("glob:*.java");

        Optional<RipgrepSearchEngine.FindResult> result = engine.findFiles(
                workspaceRoot,
                "**/*",
                "*.java",
                javaName,
                Integer.MAX_VALUE,
                10,
                Arrays.asList(".git/**", "target/**"),
                false,
                StopSignal.none());

        assertTrue(result.isPresent());
        assertEquals(
                Collections.singletonList(workspaceRoot.resolve("src/line\nbreak.java")),
                result.get().getPaths());
        assertFalse(result.get().isTruncated());
    }

    @Test
    void findAlwaysRequestsHiddenPathsAndFiltersThemInJava() throws Exception {
        List<String> capturedArguments = new ArrayList<String>();
        RipgrepProcessRunner runner = new RipgrepProcessRunner() {
            @Override
            public <T> RipgrepProcessRunner.Result<T> run(
                    Path executable,
                    List<String> arguments,
                    Path workingDirectory,
                    StopSignal stopSignal,
                    RipgrepProcessRunner.OutputParser<T> outputParser) throws Exception {
                capturedArguments.addAll(arguments);
                T output = outputParser.parse(
                        new ByteArrayInputStream(
                                ".hidden.java\0visible.java\0".getBytes(StandardCharsets.UTF_8)),
                        () -> { });
                return new RipgrepProcessRunner.Result<T>(output, "", 0, false);
            }
        };
        RipgrepSearchEngine engine = new RipgrepSearchEngine(
                runner,
                Optional.of(new RipgrepExecutable(workspaceRoot.resolve("rg"), "15.1.0")));

        Optional<RipgrepSearchEngine.FindResult> result = engine.findFiles(
                workspaceRoot,
                "**/*",
                null,
                null,
                Integer.MAX_VALUE,
                10,
                Collections.<String>emptyList(),
                false,
                StopSignal.none());

        assertTrue(capturedArguments.contains("--hidden"));
        assertTrue(result.isPresent());
        assertEquals(
                Collections.singletonList(workspaceRoot.resolve("visible.java")),
                result.get().getPaths());
    }

    @Test
    void processStartFailureDisablesRipgrepAndAllowsJavaFallback() throws Exception {
        Path missing = workspaceRoot.resolve("missing-rg");
        RipgrepSearchEngine engine = new RipgrepSearchEngine(
                new RipgrepProcessRunner(),
                Optional.of(new RipgrepExecutable(missing, "15.1.0")));
        write("file.txt", "needle\n");

        Optional<RipgrepSearchEngine.GrepResult> result = engine.grep(
                workspaceRoot,
                "needle",
                "**/*",
                true,
                10,
                StopSignal.none());

        assertFalse(result.isPresent());
        assertFalse(engine.isAvailable());
    }

    @Test
    void ripgrepExecutionErrorsDoNotDisableTheBackend() throws Exception {
        RipgrepProcessRunner failingRunner = new RipgrepProcessRunner() {
            @Override
            public <T> RipgrepProcessRunner.Result<T> run(
                    Path executable,
                    List<String> arguments,
                    Path workingDirectory,
                    StopSignal stopSignal,
                    RipgrepProcessRunner.OutputParser<T> outputParser) {
                return new RipgrepProcessRunner.Result<T>(
                        null,
                        "simulated ripgrep error",
                        2,
                        false);
            }
        };
        RipgrepSearchEngine engine = new RipgrepSearchEngine(
                failingRunner,
                Optional.of(new RipgrepExecutable(workspaceRoot.resolve("rg"), "15.1.0")));
        write("file.txt", "content\n");

        assertThrows(
                RipgrepProcessRunner.RipgrepExecutionException.class,
                () -> engine.grep(
                        workspaceRoot,
                        "content",
                        "**/*",
                        true,
                        10,
                        StopSignal.none()));
        assertTrue(engine.isAvailable());
    }

    private RipgrepSearchEngine availableEngine() {
        Optional<RipgrepExecutable> executable = new RipgrepBinaryResolver("").resolve();
        Assumptions.assumeTrue(executable.isPresent(), "ripgrep is not installed");
        return new RipgrepSearchEngine(
                new RipgrepProcessRunner(),
                executable);
    }

    private void write(String relativePath, String content) throws Exception {
        Path path = workspaceRoot.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
