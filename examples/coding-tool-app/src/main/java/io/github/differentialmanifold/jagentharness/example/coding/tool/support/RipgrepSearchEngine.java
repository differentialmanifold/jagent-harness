package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepProcessRunner.ProcessStartException;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepProcessRunner.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RipgrepSearchEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RipgrepSearchEngine.class);
    private static final int MAX_RECORD_BYTES = 1024 * 1024;
    private static final int MAX_GREP_METADATA_BYTES = 256;
    private static final int MAX_PREVIEW_CHARS = 2000;
    private static final String INCLUDE_FILE_TYPE = "jagent";

    private final RipgrepProcessRunner processRunner;
    private final AtomicReference<RipgrepExecutable> executable;

    public RipgrepSearchEngine(RipgrepProcessRunner processRunner,
                               Optional<RipgrepExecutable> executable) {
        this.processRunner = processRunner;
        this.executable = new AtomicReference<RipgrepExecutable>(executable.orElse(null));
    }

    public static RipgrepSearchEngine unavailable() {
        return new RipgrepSearchEngine(
                new RipgrepProcessRunner(),
                Optional.<RipgrepExecutable>empty());
    }

    public boolean isAvailable() {
        return executable.get() != null;
    }

    public Optional<RipgrepExecutable> getExecutable() {
        return Optional.ofNullable(executable.get());
    }

    public Optional<GrepResult> grep(Path target,
                                     String pattern,
                                     String glob,
                                     boolean ignoreCase,
                                     boolean literal,
                                     int limit,
                                     StopSignal stopSignal) throws Exception {
        RipgrepExecutable selected = executable.get();
        if (selected == null) {
            return Optional.empty();
        }

        Path workingDirectory = Files.isDirectory(target) ? target : target.getParent();
        boolean directorySearch = Files.isDirectory(target);
        String searchPath = directorySearch ? "." : target.getFileName().toString();
        SearchFileMatcher fileMatcher = directorySearch && !isBlank(glob)
                ? new SearchFileMatcher(glob)
                : null;
        List<String> arguments = new ArrayList<String>();
        arguments.add("--no-config");
        arguments.add("--null");
        arguments.add("--with-filename");
        arguments.add("--line-number");
        arguments.add("--color=never");
        // Keep every match in the NUL-delimited protocol and align line anchors
        // with BufferedReader's CRLF handling in the Java fallback.
        arguments.add("--text");
        arguments.add("--crlf");
        // Keep output bounded even when a legal source line is arbitrarily long.
        // The parser only uses the NUL-safe path and line number, then streams the
        // preview from disk.
        arguments.add("--max-columns=1");
        if (directorySearch) {
            addDirectoryPolicy(arguments);
        }
        if (literal) {
            arguments.add("--fixed-strings");
        }
        if (ignoreCase) {
            arguments.add("--ignore-case");
        }
        if (fileMatcher != null) {
            addSafeIncludeFileType(arguments, glob);
        }
        arguments.add("-e");
        arguments.add(pattern);
        arguments.add("--");
        arguments.add(searchPath);

        try {
            Result<GrepLocationResult> processResult = processRunner.run(
                    selected.getPath(),
                    arguments,
                    workingDirectory,
                    stopSignal,
                    new GrepOutputParser(workingDirectory, fileMatcher, directorySearch, limit));
            requireSuccessfulExit(processResult);
            return Optional.of(readGrepPreviews(processResult.getOutput(), stopSignal));
        } catch (ProcessStartException e) {
            disableAfterStartFailure(selected, e);
            return Optional.empty();
        }
    }

    private GrepResult readGrepPreviews(GrepLocationResult locations,
                                        StopSignal stopSignal) throws IOException {
        List<GrepMatch> matches = new ArrayList<GrepMatch>(locations.getLocations().size());
        GrepPreviewReader previewReader = new GrepPreviewReader(stopSignal);
        try {
            for (GrepLocation location : locations.getLocations()) {
                stopSignal.throwIfAborted();
                matches.add(new GrepMatch(
                        location.getPath(),
                        location.getLine(),
                        previewReader.read(location.getPath(), location.getLine())));
            }
        } finally {
            previewReader.close();
        }
        return new GrepResult(matches, locations.isTruncated());
    }

    public Optional<FindResult> findFiles(Path root,
                                          String pattern,
                                          int limit,
                                          StopSignal stopSignal) throws Exception {
        RipgrepExecutable selected = executable.get();
        if (selected == null) {
            return Optional.empty();
        }

        List<String> arguments = new ArrayList<String>();
        SearchFileMatcher fileMatcher = new SearchFileMatcher(pattern);
        arguments.add("--no-config");
        arguments.add("--files");
        arguments.add("--null");
        arguments.add("--path-separator=/");
        addDirectoryPolicy(arguments);
        addSafeIncludeFileType(arguments, pattern);
        arguments.add("--");
        arguments.add(".");

        try {
            Result<FindResult> processResult = processRunner.run(
                    selected.getPath(),
                    arguments,
                    root,
                    stopSignal,
                    new FindOutputParser(root, fileMatcher, limit));
            requireSuccessfulExit(processResult);
            return Optional.of(processResult.getOutput());
        } catch (ProcessStartException e) {
            disableAfterStartFailure(selected, e);
            return Optional.empty();
        }
    }

    private void requireSuccessfulExit(Result<?> result) throws IOException {
        if (result.isTerminatedForLimit() || result.getExitCode() == 0 || result.getExitCode() == 1) {
            return;
        }
        String stderr = result.getStderr() == null ? "" : result.getStderr().trim();
        String message = stderr.isEmpty()
                ? "ripgrep exited with code " + result.getExitCode()
                : stderr;
        throw new RipgrepProcessRunner.RipgrepExecutionException(message);
    }

    private void disableAfterStartFailure(RipgrepExecutable selected, ProcessStartException failure) {
        if (executable.compareAndSet(selected, null)) {
            LOGGER.warn(
                    "ripgrep could no longer be started at {}; falling back to the Java search implementation: {}",
                    selected.getPath(),
                    failure.getMessage());
        }
    }

    private static class GrepOutputParser implements RipgrepProcessRunner.OutputParser<GrepLocationResult> {

        private final Path workingDirectory;
        private final SearchFileMatcher fileMatcher;
        private final boolean directorySearch;
        private final int limit;

        private GrepOutputParser(Path workingDirectory,
                                 SearchFileMatcher fileMatcher,
                                 boolean directorySearch,
                                 int limit) {
            this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
            this.fileMatcher = fileMatcher;
            this.directorySearch = directorySearch;
            this.limit = limit;
        }

        @Override
        public GrepLocationResult parse(InputStream stdout, Runnable terminateForLimit) throws Exception {
            List<GrepLocation> locations = new ArrayList<GrepLocation>();
            boolean[] truncated = new boolean[] { false };
            readGrepRecords(stdout, (rawPath, line) -> {
                Path path = Paths.get(rawPath);
                if (!path.isAbsolute()) {
                    path = workingDirectory.resolve(path);
                }
                path = path.toAbsolutePath().normalize();
                Path relative = workingDirectory.relativize(path);
                if (directorySearch
                        && (SearchFileMatcher.containsExcludedDirectory(relative)
                        || (fileMatcher != null && !fileMatcher.matches(relative)))) {
                    return true;
                }
                if (locations.size() >= limit) {
                    truncated[0] = true;
                    terminateForLimit.run();
                    return false;
                }
                locations.add(new GrepLocation(path, line));
                return true;
            });
            return new GrepLocationResult(locations, truncated[0]);
        }
    }

    private static class FindOutputParser implements RipgrepProcessRunner.OutputParser<FindResult> {

        private final Path root;
        private final SearchFileMatcher fileMatcher;
        private final int limit;

        private FindOutputParser(Path root, SearchFileMatcher fileMatcher, int limit) {
            this.root = root.toAbsolutePath().normalize();
            this.fileMatcher = fileMatcher;
            this.limit = limit;
        }

        @Override
        public FindResult parse(InputStream stdout, Runnable terminateForLimit) throws Exception {
            List<Path> paths = new ArrayList<Path>();
            boolean[] truncated = new boolean[] { false };
            readDelimitedRecords(stdout, (byte) 0, record -> {
                if (record.length == 0) {
                    return true;
                }
                String rawPath = new String(record, StandardCharsets.UTF_8);
                Path relative = Paths.get(rawPath).normalize();
                if (SearchFileMatcher.containsExcludedDirectory(relative)
                        || !fileMatcher.matches(relative)) {
                    return true;
                }
                if (paths.size() >= limit) {
                    truncated[0] = true;
                    terminateForLimit.run();
                    return false;
                }
                Path path = relative.isAbsolute() ? relative : root.resolve(relative);
                paths.add(path.toAbsolutePath().normalize());
                return true;
            });
            return new FindResult(paths, truncated[0]);
        }
    }

    private static void readDelimitedRecords(InputStream inputStream,
                                             byte delimiter,
                                             RecordConsumer consumer) throws Exception {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            for (int i = 0; i < read; i++) {
                byte value = buffer[i];
                if (value == delimiter) {
                    if (!consumer.accept(record.toByteArray())) {
                        return;
                    }
                    record.reset();
                } else {
                    if (record.size() >= MAX_RECORD_BYTES) {
                        throw new IOException("ripgrep output record exceeded " + MAX_RECORD_BYTES + " bytes");
                    }
                    record.write(value);
                }
            }
        }
        if (record.size() > 0) {
            consumer.accept(record.toByteArray());
        }
    }

    private static void readGrepRecords(InputStream inputStream,
                                        GrepRecordConsumer consumer) throws Exception {
        BufferedInputStream buffered = new BufferedInputStream(inputStream);
        while (true) {
            byte[] path = readDelimitedRecord(
                    buffered,
                    (byte) 0,
                    MAX_RECORD_BYTES,
                    "ripgrep path");
            if (path == null) {
                return;
            }
            if (path.length == 0) {
                throw new IOException("Invalid empty path in ripgrep output");
            }
            byte[] metadata = readDelimitedRecord(
                    buffered,
                    (byte) '\n',
                    MAX_GREP_METADATA_BYTES,
                    "ripgrep match metadata");
            if (metadata == null) {
                throw new IOException("Incomplete ripgrep match record");
            }
            if (!consumer.accept(
                    new String(path, StandardCharsets.UTF_8),
                    parseLineNumber(metadata))) {
                return;
            }
        }
    }

    private static byte[] readDelimitedRecord(InputStream inputStream,
                                              byte delimiter,
                                              int limit,
                                              String description) throws IOException {
        ByteArrayOutputStream record = new ByteArrayOutputStream(Math.min(limit, 8192));
        int value;
        while ((value = inputStream.read()) >= 0) {
            if ((byte) value == delimiter) {
                return record.toByteArray();
            }
            if (record.size() >= limit) {
                throw new IOException(description + " exceeded " + limit + " bytes");
            }
            record.write(value);
        }
        if (record.size() == 0) {
            return null;
        }
        throw new IOException("Incomplete " + description);
    }

    private static int parseLineNumber(byte[] metadata) throws IOException {
        int line = 0;
        int index = 0;
        while (index < metadata.length) {
            int value = metadata[index] & 0xff;
            if (value == ':') {
                if (index == 0 || line <= 0) {
                    break;
                }
                return line;
            }
            if (value < '0'
                    || value > '9'
                    || line > (Integer.MAX_VALUE - (value - '0')) / 10) {
                break;
            }
            line = (line * 10) + (value - '0');
            index++;
        }
        throw new IOException("Invalid line number in ripgrep output");
    }

    private static class GrepPreviewReader implements Closeable {

        private final StopSignal stopSignal;
        private Path currentPath;
        private StreamingLineReader reader;
        private int currentLine;

        private GrepPreviewReader(StopSignal stopSignal) {
            this.stopSignal = stopSignal;
        }

        private String read(Path path, int line) throws IOException {
            if (reader == null || !path.equals(currentPath) || line <= currentLine) {
                closeCurrent();
                currentPath = path;
                reader = new StreamingLineReader(path, stopSignal);
                currentLine = 0;
            }
            while (currentLine < line) {
                String preview = reader.readPreview();
                if (preview == null) {
                    throw new IOException(
                            "File ended before ripgrep match line " + line + ": " + path);
                }
                currentLine++;
                if (currentLine == line) {
                    return preview;
                }
            }
            throw new IOException("Invalid ripgrep match line " + line + ": " + path);
        }

        @Override
        public void close() throws IOException {
            closeCurrent();
        }

        private void closeCurrent() throws IOException {
            if (reader != null) {
                reader.close();
                reader = null;
            }
        }
    }

    private static class StreamingLineReader implements Closeable {

        private static final int NO_PENDING_CHAR = -2;

        private final BufferedReader reader;
        private final StopSignal stopSignal;
        private int pendingChar = NO_PENDING_CHAR;
        private int charsUntilStopCheck = 8192;

        private StreamingLineReader(Path path, StopSignal stopSignal) throws IOException {
            this.reader = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8));
            this.stopSignal = stopSignal;
        }

        private String readPreview() throws IOException {
            StringBuilder preview = new StringBuilder(MAX_PREVIEW_CHARS);
            boolean readAny = false;
            boolean contentStarted = false;
            long contentLength = 0;
            long trailingWhitespace = 0;

            while (true) {
                int value = readChar();
                if (value < 0 || value == '\n' || value == '\r') {
                    if (value == '\r') {
                        int following = readChar();
                        if (following >= 0 && following != '\n') {
                            pendingChar = following;
                        }
                    }
                    if (!readAny && value < 0) {
                        return null;
                    }
                    long trimmedLength = contentLength - trailingWhitespace;
                    if (preview.length() > trimmedLength) {
                        preview.setLength((int) trimmedLength);
                    }
                    if (trimmedLength > MAX_PREVIEW_CHARS) {
                        preview.append("...");
                    }
                    return preview.toString();
                }

                readAny = true;
                char character = (char) value;
                if (!contentStarted && character <= ' ') {
                    continue;
                }
                contentStarted = true;
                contentLength++;
                if (character <= ' ') {
                    trailingWhitespace++;
                } else {
                    trailingWhitespace = 0;
                }
                if (preview.length() < MAX_PREVIEW_CHARS) {
                    preview.append(character);
                }
            }
        }

        private int readChar() throws IOException {
            if (--charsUntilStopCheck <= 0) {
                stopSignal.throwIfAborted();
                charsUntilStopCheck = 8192;
            }
            if (pendingChar != NO_PENDING_CHAR) {
                int value = pendingChar;
                pendingChar = NO_PENDING_CHAR;
                return value;
            }
            return reader.read();
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void addDirectoryPolicy(List<String> arguments) {
        arguments.add("--hidden");
        for (String directoryName : SearchFileMatcher.excludedDirectoryNames()) {
            arguments.add("--glob");
            arguments.add("!" + directoryName + "/**");
            arguments.add("--glob");
            arguments.add("!**/" + directoryName + "/**");
        }
    }

    private static void addSafeIncludeFileType(List<String> arguments, String glob) {
        // A positive --glob overrides matching .gitignore rules in ripgrep.
        // A basename-only temporary file type preserves ignore precedence. More
        // expressive path globs are filtered by the streaming output parser.
        Optional<String> fileTypeGlob = SearchFileMatcher.ripgrepFileTypeGlob(glob);
        if (!fileTypeGlob.isPresent()) {
            return;
        }
        arguments.add("--type-add");
        arguments.add(INCLUDE_FILE_TYPE + ":" + fileTypeGlob.get());
        arguments.add("--type");
        arguments.add(INCLUDE_FILE_TYPE);
    }

    private interface RecordConsumer {

        boolean accept(byte[] record) throws Exception;
    }

    private interface GrepRecordConsumer {

        boolean accept(String path, int line) throws Exception;
    }

    private static class GrepLocation {

        private final Path path;
        private final int line;

        private GrepLocation(Path path, int line) {
            this.path = path;
            this.line = line;
        }

        private Path getPath() {
            return path;
        }

        private int getLine() {
            return line;
        }
    }

    private static class GrepLocationResult {

        private final List<GrepLocation> locations;
        private final boolean truncated;

        private GrepLocationResult(List<GrepLocation> locations, boolean truncated) {
            this.locations = locations;
            this.truncated = truncated;
        }

        private List<GrepLocation> getLocations() {
            return locations;
        }

        private boolean isTruncated() {
            return truncated;
        }
    }

    public static class GrepResult {

        private final List<GrepMatch> matches;
        private final boolean truncated;

        public GrepResult(List<GrepMatch> matches, boolean truncated) {
            this.matches = Collections.unmodifiableList(new ArrayList<GrepMatch>(matches));
            this.truncated = truncated;
        }

        public List<GrepMatch> getMatches() {
            return matches;
        }

        public boolean isTruncated() {
            return truncated;
        }
    }

    public static class GrepMatch {

        private final Path path;
        private final int line;
        private final String preview;

        public GrepMatch(Path path, int line, String preview) {
            this.path = path;
            this.line = line;
            this.preview = preview;
        }

        public Path getPath() {
            return path;
        }

        public int getLine() {
            return line;
        }

        public String getPreview() {
            return preview;
        }
    }

    public static class FindResult {

        private final List<Path> paths;
        private final boolean truncated;

        public FindResult(List<Path> paths, boolean truncated) {
            this.paths = Collections.unmodifiableList(new ArrayList<Path>(paths));
            this.truncated = truncated;
        }

        public List<Path> getPaths() {
            return paths;
        }

        public boolean isTruncated() {
            return truncated;
        }
    }
}
