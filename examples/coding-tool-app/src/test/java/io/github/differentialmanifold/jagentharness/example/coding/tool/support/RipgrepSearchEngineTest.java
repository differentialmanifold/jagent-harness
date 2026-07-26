package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
    void portableGlobMatcherCoversTheDocumentedSubset() {
        SearchFileMatcher basename = new SearchFileMatcher("*.java");
        SearchFileMatcher recursive = new SearchFileMatcher("**/*.{java,kt}");
        SearchFileMatcher underSource = new SearchFileMatcher("src/**/*.java");
        SearchFileMatcher characterClass = new SearchFileMatcher("src/App[0-9].java");

        assertTrue(basename.matches(workspaceRoot.getFileSystem().getPath("App.java")));
        assertTrue(basename.matches(workspaceRoot.getFileSystem().getPath("src", "App.java")));
        assertTrue(recursive.matches(workspaceRoot.getFileSystem().getPath("App.kt")));
        assertTrue(recursive.matches(workspaceRoot.getFileSystem().getPath("src", "App.java")));
        assertTrue(underSource.matches(workspaceRoot.getFileSystem().getPath("src", "App.java")));
        assertTrue(underSource.matches(workspaceRoot.getFileSystem().getPath("src", "main", "App.java")));
        assertFalse(underSource.matches(workspaceRoot.getFileSystem().getPath("other", "App.java")));
        assertTrue(characterClass.matches(workspaceRoot.getFileSystem().getPath("src", "App7.java")));
        assertFalse(characterClass.matches(workspaceRoot.getFileSystem().getPath("src", "AppX.java")));
    }

    @Test
    void grepUsesRegexAndRespectsIgnoreFiles() throws Exception {
        RipgrepSearchEngine engine = availableEngine();
        Files.createDirectories(workspaceRoot.resolve(".git"));
        write(".gitignore", "ignored.txt\n");
        write("ignored.txt", "ab\n");
        write("visible.txt", "aab\n");
        write(".git/internal.txt", "ab\n");
        write("target/generated.txt", "ab\n");

        Optional<RipgrepSearchEngine.GrepResult> result = engine.grep(
                workspaceRoot,
                "a+b",
                "",
                false,
                false,
                10,
                StopSignal.none());

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getMatches().size());
        assertEquals(
                workspaceRoot.resolve("visible.txt"),
                result.get().getMatches().get(0).getPath());
        assertEquals("aab", result.get().getMatches().get(0).getPreview());
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
                "",
                false,
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
                "",
                false,
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
                "",
                false,
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
    void findUsesNullDelimitedPathsAndPushesThePattern() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().startsWith("windows"));
        RipgrepSearchEngine engine = availableEngine();
        write("src/line\nbreak.java", "content\n");
        write("src/other.txt", "content\n");

        Optional<RipgrepSearchEngine.FindResult> result = engine.findFiles(
                workspaceRoot,
                "*.java",
                10,
                StopSignal.none());

        assertTrue(result.isPresent());
        assertEquals(
                Arrays.asList(workspaceRoot.resolve("src/line\nbreak.java")),
                result.get().getPaths());
        assertFalse(result.get().isTruncated());
    }

    @Test
    void findRequestsHiddenPathsAndLetsRipgrepApplyIgnoreRules() throws Exception {
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
                "*.java",
                10,
                StopSignal.none());

        assertTrue(capturedArguments.contains("--hidden"));
        assertFalse(capturedArguments.contains("--no-ignore"));
        assertDirectoryExclusions(capturedArguments);
        assertEquals("jagent:*.java", capturedArguments.get(capturedArguments.indexOf("--type-add") + 1));
        assertEquals("jagent", capturedArguments.get(capturedArguments.indexOf("--type") + 1));
        assertTrue(result.isPresent());
        assertEquals(2, result.get().getPaths().size());
    }

    @Test
    void grepPushesModelFacingOptionsToRipgrep() throws Exception {
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
                T output = outputParser.parse(new ByteArrayInputStream(new byte[0]), () -> { });
                return new RipgrepProcessRunner.Result<T>(output, "", 1, false);
            }
        };
        RipgrepSearchEngine engine = new RipgrepSearchEngine(
                runner,
                Optional.of(new RipgrepExecutable(workspaceRoot.resolve("rg"), "15.1.0")));

        engine.grep(
                workspaceRoot,
                "Needle",
                "*.java",
                true,
                true,
                10,
                StopSignal.none());

        assertTrue(capturedArguments.contains("--hidden"));
        assertTrue(capturedArguments.contains("--ignore-case"));
        assertTrue(capturedArguments.contains("--fixed-strings"));
        assertTrue(capturedArguments.contains("--text"));
        assertTrue(capturedArguments.contains("--crlf"));
        assertFalse(capturedArguments.contains("--no-ignore"));
        assertDirectoryExclusions(capturedArguments);
        assertEquals("jagent:*.java", capturedArguments.get(capturedArguments.indexOf("--type-add") + 1));
        assertEquals("jagent", capturedArguments.get(capturedArguments.indexOf("--type") + 1));
    }

    @Test
    void findPostFiltersPathAwareGlobsWithoutUnsafeRipgrepIncludes() throws Exception {
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
                                ("src/App.java\0"
                                        + "src/main/App.java\0"
                                        + "other/App.java\0").getBytes(StandardCharsets.UTF_8)),
                        () -> { });
                return new RipgrepProcessRunner.Result<T>(output, "", 0, false);
            }
        };
        RipgrepSearchEngine engine = new RipgrepSearchEngine(
                runner,
                Optional.of(new RipgrepExecutable(workspaceRoot.resolve("rg"), "15.1.0")));

        Optional<RipgrepSearchEngine.FindResult> result = engine.findFiles(
                workspaceRoot,
                "src/**/*.java",
                10,
                StopSignal.none());

        assertFalse(capturedArguments.contains("--type-add"));
        assertTrue(result.isPresent());
        assertEquals(
                Arrays.asList(
                        workspaceRoot.resolve("src/App.java"),
                        workspaceRoot.resolve("src/main/App.java")),
                result.get().getPaths());
    }

    @Test
    void grepPostFiltersPathAwareGlobsWithoutUnsafeRipgrepIncludes() throws Exception {
        write("src/App.java", "needle\n");
        write("other/App.java", "needle\n");
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
                                ("src/App.java\0" + "1:\n"
                                        + "other/App.java\0" + "1:\n").getBytes(StandardCharsets.UTF_8)),
                        () -> { });
                return new RipgrepProcessRunner.Result<T>(output, "", 0, false);
            }
        };
        RipgrepSearchEngine engine = new RipgrepSearchEngine(
                runner,
                Optional.of(new RipgrepExecutable(workspaceRoot.resolve("rg"), "15.1.0")));

        Optional<RipgrepSearchEngine.GrepResult> result = engine.grep(
                workspaceRoot,
                "needle",
                "src/**/*.java",
                false,
                true,
                10,
                StopSignal.none());

        assertFalse(capturedArguments.contains("--type-add"));
        assertTrue(result.isPresent());
        assertEquals(1, result.get().getMatches().size());
        assertEquals(workspaceRoot.resolve("src/App.java"), result.get().getMatches().get(0).getPath());
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
                "",
                false,
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
                        "",
                        false,
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

    private void assertDirectoryExclusions(List<String> arguments) {
        assertTrue(arguments.contains("!.git/**"));
        assertTrue(arguments.contains("!**/.git/**"));
        assertTrue(arguments.contains("!target/**"));
        assertTrue(arguments.contains("!**/target/**"));
        assertTrue(arguments.contains("!node_modules/**"));
        assertTrue(arguments.contains("!**/node_modules/**"));
    }

    private void write(String relativePath, String content) throws Exception {
        Path path = workspaceRoot.resolve(relativePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
