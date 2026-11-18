package io.github.differentialmanifold.jagentharness.tools.coding.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.protocol.ImageFormat;
import io.github.differentialmanifold.jagentharness.tools.coding.tool.support.ContentHashing;
import io.github.differentialmanifold.jagentharness.tools.coding.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.tools.coding.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.tools.coding.tool.support.WorkspacePathResolver;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReadTool implements ToolDefinition {

    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LIMIT = 2000;

    private final ObjectMapper objectMapper;
    private final WorkspacePathResolver pathResolver;

    public ReadTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
    }

    @Override
    public String getName() {
        return "read";
    }

    @Override
    public String getDescription() {
        return "Read a PNG, JPEG, GIF or WebP as an image attachment, or read UTF-8 text lines with the file's SHA-256 hash. Relative paths resolve from the workspace; absolute paths are allowed. Text supports offset and limit for reading a specific line range.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set(
                "path",
                ToolSchemas.stringProperty(
                        objectMapper, "Workspace-relative or absolute file path."));
        properties.set(
                "offset",
                ToolSchemas.integerProperty(
                        objectMapper, "One-based line number to start reading from. Default 1."));
        properties.set(
                "limit",
                ToolSchemas.integerProperty(
                        objectMapper,
                        "Maximum number of lines to return. Default and maximum 2000."));
        return ToolSchemas.objectSchema(objectMapper, properties, "path");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        context.getStopSignal().throwIfAborted();
        Path path = pathResolver.resolve(context, ToolArguments.requiredText(arguments, "path"));
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    "File not found: " + pathResolver.relative(context, path));
        }

        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            input.mark(12);
            byte[] header = new byte[12];
            int count = 0;
            while (count < header.length) {
                int n = input.read(header, count, header.length - count);
                if (n < 0) break;
                count += n;
            }
            String mediaType = ImageFormat.detect(java.util.Arrays.copyOf(header, count));
            if (mediaType != null) {
                if (arguments.has("offset") || arguments.has("limit"))
                    throw new IllegalArgumentException("Line ranges apply only to text files");
                if (Files.size(path) > ImageFormat.MAX_BYTES)
                    throw new IllegalArgumentException("Each image must be at most 10 MB");
                input.reset();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    context.getStopSignal().throwIfAborted();
                    if (bytes.size() + n > ImageFormat.MAX_BYTES)
                        throw new IllegalArgumentException("Each image must be at most 10 MB");
                    bytes.write(buffer, 0, n);
                }
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("name", path.getFileName().toString());
                image.put("mediaType", mediaType);
                image.put(
                        "url",
                        "data:"
                                + mediaType
                                + ";base64,"
                                + Base64.getEncoder().encodeToString(bytes.toByteArray()));
                ObjectNode result =
                        objectMapper
                                .createObjectNode()
                                .put("type", "image")
                                .put("path", pathResolver.relative(context, path));
                return ToolExecutionResult.success(result)
                        .withImages(Collections.singletonList(image));
            }
        }
        int offset = arguments.path("offset").asInt(1);
        int limit = arguments.path("limit").asInt(DEFAULT_LIMIT);
        if (offset < 1) {
            throw new IllegalArgumentException("offset must be at least 1");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }

        ReadSlice slice = readLines(context, path, offset, limit);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", pathResolver.relative(context, path));
        result.put("type", "text");
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("lines", slice.lines.size());
        result.put("totalLines", slice.totalLines);
        result.put("truncated", slice.hasMore);
        result.put("contentHash", slice.contentHash);
        result.put("content", String.join("\n", slice.lines));
        return ToolExecutionResult.success(result);
    }

    private ReadSlice readLines(ToolContext context, Path path, int offset, int limit)
            throws Exception {
        List<String> selected = new ArrayList<String>();
        int totalLines = 0;
        MessageDigest digest = ContentHashing.newSha256Digest();
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest);
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        input, StandardCharsets.UTF_8.newDecoder()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                context.getStopSignal().throwIfAborted();
                totalLines++;
                if (totalLines == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                if (line.indexOf('\0') >= 0) {
                    throw unsupportedTextFile(path);
                }
                if (totalLines >= offset && selected.size() < limit) {
                    selected.add(line);
                }
            }
        } catch (MalformedInputException e) {
            throw unsupportedTextFile(path);
        }
        int selectedEnd = Math.min(offset - 1, totalLines) + selected.size();
        return new ReadSlice(
                selected,
                totalLines,
                selectedEnd < totalLines,
                ContentHashing.toHex(digest.digest()));
    }

    private IllegalArgumentException unsupportedTextFile(Path path) {
        return new IllegalArgumentException(
                "Read tool supports UTF-8 text, PNG, JPEG, WebP and GIF: " + path.getFileName());
    }

    private static class ReadSlice {
        private final List<String> lines;
        private final int totalLines;
        private final boolean hasMore;
        private final String contentHash;

        private ReadSlice(List<String> lines, int totalLines, boolean hasMore, String contentHash) {
            this.lines = lines;
            this.totalLines = totalLines;
            this.hasMore = hasMore;
            this.contentHash = contentHash;
        }
    }
}
