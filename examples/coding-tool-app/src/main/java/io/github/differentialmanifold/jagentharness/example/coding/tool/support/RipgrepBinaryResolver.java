package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves an existing local ripgrep binary without downloading or invoking a shell.
 */
public final class RipgrepBinaryResolver {

    static final String CONFIGURED_PATH_NAME = "JAGENT_RG_PATH";

    private final String configuredPath;
    private final Map<String, String> environment;
    private final Platform platform;
    private final VersionProbe versionProbe;

    private volatile Optional<RipgrepExecutable> cached;

    public RipgrepBinaryResolver(String configuredPath) {
        this(configuredPath, System.getenv(), Platform.current(), new ProcessVersionProbe());
    }

    RipgrepBinaryResolver(
            String configuredPath,
            Map<String, String> environment,
            Platform platform,
            VersionProbe versionProbe) {
        this.configuredPath = configuredPath;
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                environment == null ? Collections.<String, String>emptyMap() : environment));
        this.platform = platform == null ? Platform.current() : platform;
        this.versionProbe = versionProbe == null ? new ProcessVersionProbe() : versionProbe;
    }

    /**
     * Finds and verifies ripgrep once, then returns the cached result on later calls.
     */
    public Optional<RipgrepExecutable> resolve() {
        Optional<RipgrepExecutable> result = cached;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            if (cached == null) {
                cached = resolveUncached();
            }
            return cached;
        }
    }

    private Optional<RipgrepExecutable> resolveUncached() {
        String explicit = trimToNull(configuredPath);
        if (explicit != null) {
            return Optional.of(resolveConfigured(explicit));
        }

        for (Path candidate : pathCandidates()) {
            Optional<RipgrepExecutable> executable = probe(candidate, false);
            if (executable.isPresent()) {
                return executable;
            }
        }
        return Optional.empty();
    }

    private RipgrepExecutable resolveConfigured(String value) {
        final Path configured;
        try {
            configured = Paths.get(value);
        } catch (InvalidPathException exception) {
            throw configurationError("is not a valid path: " + value, exception);
        }
        if (!configured.isAbsolute()) {
            throw configurationError("must be an absolute path: " + value, null);
        }

        Path normalized = configured.normalize();
        if (!isUsableFile(normalized)) {
            throw configurationError("does not point to a usable executable file: " + normalized, null);
        }
        Optional<RipgrepExecutable> executable = probe(normalized, true);
        if (!executable.isPresent()) {
            throw configurationError("is not a usable ripgrep executable: " + normalized, null);
        }
        return executable.get();
    }

    private List<Path> pathCandidates() {
        String pathValue = pathEnvironmentValue();
        if (trimToNull(pathValue) == null) {
            return Collections.emptyList();
        }

        List<Path> candidates = new ArrayList<Path>();
        String[] directories = pathValue.split(Pattern.quote(platform.pathSeparator()), -1);
        for (String rawDirectory : directories) {
            String directoryValue = unquote(trimToNull(rawDirectory));
            if (directoryValue == null) {
                continue;
            }
            try {
                Path directory = Paths.get(directoryValue);
                Path candidate = findExecutableInDirectory(directory);
                if (candidate != null && isUsableFile(candidate)) {
                    candidates.add(candidate.toAbsolutePath().normalize());
                }
            } catch (InvalidPathException ignored) {
                // An invalid PATH entry must not hide valid entries that follow it.
            }
        }
        return candidates;
    }

    private String pathEnvironmentValue() {
        String exact = environment.get("PATH");
        if (exact != null || !platform.isWindows()) {
            return exact;
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if ("PATH".equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Path findExecutableInDirectory(Path directory) {
        Path exact = directory.resolve(platform.executableName());
        if (!platform.isWindows()) {
            return exact;
        }

        // Windows environment variables and executable names are case-insensitive,
        // including on less common case-sensitive Windows file systems.
        if (!Files.isDirectory(directory)) {
            return exact;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                if (fileName != null && platform.executableName().equalsIgnoreCase(fileName.toString())) {
                    return entry;
                }
            }
        } catch (IOException ignored) {
            // The candidate will be treated as unavailable.
        }
        return exact;
    }

    private boolean isUsableFile(Path path) {
        return Files.isRegularFile(path) && (platform.isWindows() || Files.isExecutable(path));
    }

    private Optional<RipgrepExecutable> probe(Path candidate, boolean configured) {
        try {
            String version = trimToNull(versionProbe.probe(candidate));
            if (version == null) {
                if (configured) {
                    throw configurationError(
                            "did not report a ripgrep version: " + candidate,
                            null);
                }
                return Optional.empty();
            }
            return Optional.of(new RipgrepExecutable(candidate.toAbsolutePath().normalize(), version));
        } catch (IOException exception) {
            if (configured) {
                throw configurationError(
                        "is not a usable ripgrep executable: " + candidate,
                        exception);
            }
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking ripgrep executable: " + candidate, exception);
        }
    }

    private static IllegalArgumentException configurationError(String message, Throwable cause) {
        String fullMessage = CONFIGURED_PATH_NAME + " " + message;
        return cause == null
                ? new IllegalArgumentException(fullMessage)
                : new IllegalArgumentException(fullMessage, cause);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String unquote(String value) {
        if (value != null && value.length() >= 2
                && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return trimToNull(value.substring(1, value.length() - 1));
        }
        return value;
    }

    enum Platform {
        UNIX(false, ":", "rg"),
        WINDOWS(true, ";", "rg.exe");

        private final boolean windows;
        private final String pathSeparator;
        private final String executableName;

        Platform(boolean windows, String pathSeparator, String executableName) {
            this.windows = windows;
            this.pathSeparator = pathSeparator;
            this.executableName = executableName;
        }

        static Platform current() {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return osName.startsWith("windows") ? WINDOWS : UNIX;
        }

        boolean isWindows() {
            return windows;
        }

        String pathSeparator() {
            return pathSeparator;
        }

        String executableName() {
            return executableName;
        }
    }

    interface VersionProbe {
        String probe(Path executable) throws IOException, InterruptedException;
    }

    static final class ProcessVersionProbe implements VersionProbe {

        private static final long TIMEOUT_SECONDS = 2L;
        private static final long TERMINATION_GRACE_MILLIS = 200L;
        private static final long OUTPUT_JOIN_MILLIS = 500L;
        private static final int MAX_OUTPUT_BYTES = 4096;
        private static final Pattern VERSION_LINE =
                Pattern.compile("^ripgrep\\s+([^\\s]+)(?:\\s+.*)?$");

        @Override
        public String probe(Path executable) throws IOException, InterruptedException {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    Arrays.asList(executable.toString(), "--version"));
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            BoundedOutputCollector collector = new BoundedOutputCollector(MAX_OUTPUT_BYTES);
            Thread outputThread = new Thread(new StreamCollector(process.getInputStream(), collector),
                    "ripgrep-version-output");
            outputThread.setDaemon(true);
            outputThread.start();

            boolean finished;
            try {
                finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                terminateImmediately(process);
                throw exception;
            }

            if (!finished) {
                terminate(process);
                joinOutput(outputThread, process);
                throw new IOException("ripgrep version check timed out after " + TIMEOUT_SECONDS + " seconds");
            }

            joinOutput(outputThread, process);
            if (collector.getFailure() != null) {
                throw collector.getFailure();
            }
            if (collector.isTruncated()) {
                throw new IOException("ripgrep version output exceeded " + MAX_OUTPUT_BYTES + " bytes");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ripgrep version check exited with code " + process.exitValue());
            }
            return parseVersion(collector.asString());
        }

        static String parseVersion(String output) throws IOException {
            String normalized = output == null ? "" : output.replace("\r\n", "\n");
            int lineEnd = normalized.indexOf('\n');
            String firstLine = (lineEnd >= 0 ? normalized.substring(0, lineEnd) : normalized).trim();
            Matcher matcher = VERSION_LINE.matcher(firstLine);
            if (!matcher.matches()) {
                throw new IOException("executable did not identify itself as ripgrep");
            }
            return matcher.group(1);
        }

        private static void joinOutput(Thread outputThread, Process process)
                throws IOException, InterruptedException {
            outputThread.join(OUTPUT_JOIN_MILLIS);
            if (outputThread.isAlive()) {
                closeQuietly(process.getInputStream());
                outputThread.interrupt();
                throw new IOException("ripgrep version output did not close");
            }
        }

        private static void terminate(Process process) throws InterruptedException {
            process.destroy();
            try {
                if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                throw exception;
            }
        }

        private static void terminateImmediately(Process process) {
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        private static void closeQuietly(InputStream input) {
            try {
                input.close();
            } catch (IOException ignored) {
                // Best effort while stopping a failed process.
            }
        }
    }

    private static final class StreamCollector implements Runnable {

        private final InputStream input;
        private final BoundedOutputCollector collector;

        private StreamCollector(InputStream input, BoundedOutputCollector collector) {
            this.input = input;
            this.collector = collector;
        }

        @Override
        public void run() {
            try (InputStream stream = input) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    collector.append(buffer, count);
                }
            } catch (IOException exception) {
                collector.fail(exception);
            }
        }
    }

    private static final class BoundedOutputCollector {

        private final int limit;
        private final ByteArrayOutputStream output;
        private volatile boolean truncated;
        private volatile IOException failure;

        private BoundedOutputCollector(int limit) {
            this.limit = limit;
            this.output = new ByteArrayOutputStream(limit);
        }

        private synchronized void append(byte[] bytes, int count) {
            int remaining = limit - output.size();
            int accepted = Math.min(Math.max(remaining, 0), count);
            if (accepted > 0) {
                output.write(bytes, 0, accepted);
            }
            if (accepted < count) {
                truncated = true;
            }
        }

        private void fail(IOException exception) {
            failure = exception;
        }

        private boolean isTruncated() {
            return truncated;
        }

        private IOException getFailure() {
            return failure;
        }

        private synchronized String asString() {
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
