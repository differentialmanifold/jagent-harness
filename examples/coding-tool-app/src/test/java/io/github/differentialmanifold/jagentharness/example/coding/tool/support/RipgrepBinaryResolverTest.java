package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RipgrepBinaryResolverTest {

    @TempDir
    Path tempDirectory;

    @Test
    void configuredPathWinsOverPathAndResultIsCached() throws Exception {
        Path configured = createExecutable(tempDirectory.resolve("configured"), "custom-rg");
        Path pathExecutable = createExecutable(tempDirectory.resolve("on-path"), "rg");
        Map<String, String> environment = Collections.singletonMap(
                "PATH",
                pathExecutable.getParent().toString());
        AtomicInteger probes = new AtomicInteger();

        RipgrepBinaryResolver resolver = new RipgrepBinaryResolver(
                configured.toString(),
                environment,
                RipgrepBinaryResolver.Platform.UNIX,
                executable -> {
                    probes.incrementAndGet();
                    assertEquals(configured, executable);
                    return "15.1.0";
                });

        Optional<RipgrepExecutable> first = resolver.resolve();
        Optional<RipgrepExecutable> second = resolver.resolve();

        assertTrue(first.isPresent());
        assertSame(first, second);
        assertEquals(configured.toAbsolutePath().normalize(), first.get().getPath());
        assertEquals("15.1.0", first.get().getVersion());
        assertEquals(1, probes.get());
    }

    @Test
    void rejectsRelativeConfiguredPathWithoutProbing() {
        AtomicInteger probes = new AtomicInteger();
        RipgrepBinaryResolver resolver = new RipgrepBinaryResolver(
                "bin/rg",
                Collections.<String, String>emptyMap(),
                RipgrepBinaryResolver.Platform.UNIX,
                executable -> {
                    probes.incrementAndGet();
                    return "15.1.0";
                });

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, resolver::resolve);

        assertTrue(exception.getMessage().contains("JAGENT_RG_PATH must be an absolute path"));
        assertEquals(0, probes.get());
    }

    @Test
    void rejectsMissingConfiguredPathInsteadOfFallingBackToPath() throws Exception {
        Path pathExecutable = createExecutable(tempDirectory.resolve("on-path"), "rg");
        Path missing = tempDirectory.resolve("missing-rg").toAbsolutePath();
        Map<String, String> environment = Collections.singletonMap(
                "PATH",
                pathExecutable.getParent().toString());

        RipgrepBinaryResolver resolver = new RipgrepBinaryResolver(
                missing.toString(),
                environment,
                RipgrepBinaryResolver.Platform.UNIX,
                executable -> "15.1.0");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, resolver::resolve);

        assertTrue(exception.getMessage().contains("does not point to a usable executable file"));
    }

    @Test
    void rejectsConfiguredExecutableThatDoesNotIdentifyAsRipgrep() throws Exception {
        Path configured = createExecutable(tempDirectory, "not-rg");
        RipgrepBinaryResolver resolver = new RipgrepBinaryResolver(
                configured.toString(),
                Collections.<String, String>emptyMap(),
                RipgrepBinaryResolver.Platform.UNIX,
                executable -> {
                    throw new IOException("not ripgrep");
                });

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, resolver::resolve);

        assertTrue(exception.getMessage().contains("is not a usable ripgrep executable"));
        assertTrue(exception.getCause() instanceof IOException);
    }

    @Test
    void scansUnixPathInOrderAndSkipsInvalidCandidate() throws Exception {
        Path first = createExecutable(tempDirectory.resolve("first"), "rg");
        Path second = createExecutable(tempDirectory.resolve("second"), "rg");
        Map<String, String> environment = Collections.singletonMap(
                "PATH",
                first.getParent() + ":" + second.getParent());
        AtomicInteger probes = new AtomicInteger();

        RipgrepBinaryResolver resolver = new RipgrepBinaryResolver(
                null,
                environment,
                RipgrepBinaryResolver.Platform.UNIX,
                executable -> {
                    probes.incrementAndGet();
                    if (executable.equals(first)) {
                        throw new IOException("wrong executable");
                    }
                    return "14.1.1";
                });

        Optional<RipgrepExecutable> result = resolver.resolve();

        assertTrue(result.isPresent());
        assertEquals(second, result.get().getPath());
        assertEquals("14.1.1", result.get().getVersion());
        assertEquals(2, probes.get());
    }

    @Test
    void findsWindowsExecutableAndPathVariableCaseInsensitively() throws Exception {
        Path executable = createRegularFile(tempDirectory.resolve("Windows Tools"), "RG.EXE");
        Map<String, String> environment = Collections.singletonMap(
                "Path",
                '"' + executable.getParent().toString() + '"');

        RipgrepBinaryResolver resolver = new RipgrepBinaryResolver(
                null,
                environment,
                RipgrepBinaryResolver.Platform.WINDOWS,
                candidate -> {
                    assertEquals(executable, candidate);
                    return "15.0.0";
                });

        Optional<RipgrepExecutable> result = resolver.resolve();

        assertTrue(result.isPresent());
        assertEquals(executable, result.get().getPath());
    }

    @Test
    void returnsAndCachesEmptyWhenPathContainsNoRipgrep() {
        AtomicInteger probes = new AtomicInteger();
        RipgrepBinaryResolver resolver = new RipgrepBinaryResolver(
                null,
                Collections.singletonMap("PATH", tempDirectory.toString()),
                RipgrepBinaryResolver.Platform.UNIX,
                executable -> {
                    probes.incrementAndGet();
                    return "15.1.0";
                });

        Optional<RipgrepExecutable> first = resolver.resolve();
        Optional<RipgrepExecutable> second = resolver.resolve();

        assertFalse(first.isPresent());
        assertSame(first, second);
        assertEquals(0, probes.get());
    }

    @Test
    void ignoresEmptyAndInvalidPathEntries() throws Exception {
        Path executable = createExecutable(tempDirectory.resolve("valid"), "rg");
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("PATH", "::\u0000invalid:" + executable.getParent());

        RipgrepBinaryResolver resolver = new RipgrepBinaryResolver(
                " ",
                environment,
                RipgrepBinaryResolver.Platform.UNIX,
                candidate -> "13.0.0");

        Optional<RipgrepExecutable> result = resolver.resolve();

        assertTrue(result.isPresent());
        assertEquals(executable, result.get().getPath());
    }

    @Test
    void parsesOnlyRipgrepVersionBanner() throws Exception {
        assertEquals(
                "15.1.0",
                RipgrepBinaryResolver.ProcessVersionProbe.parseVersion(
                        "ripgrep 15.1.0\r\n-SIMD -AVX"));
        assertEquals(
                "14.1.1",
                RipgrepBinaryResolver.ProcessVersionProbe.parseVersion(
                        "ripgrep 14.1.1 (rev 4649aa9700)\n"));

        assertThrows(
                IOException.class,
                () -> RipgrepBinaryResolver.ProcessVersionProbe.parseVersion("rg 15.1.0\n"));
        assertThrows(
                IOException.class,
                () -> RipgrepBinaryResolver.ProcessVersionProbe.parseVersion("ripgrep\n"));
    }

    private Path createExecutable(Path directory, String name) throws IOException {
        Path executable = createRegularFile(directory, name);
        assertTrue(executable.toFile().setExecutable(true) || Files.isExecutable(executable));
        return executable;
    }

    private Path createRegularFile(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        return Files.createFile(directory.resolve(name)).toAbsolutePath().normalize();
    }
}
