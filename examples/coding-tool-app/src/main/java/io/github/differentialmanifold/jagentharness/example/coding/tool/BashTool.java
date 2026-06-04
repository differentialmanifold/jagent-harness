package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class BashTool implements ToolDefinition {

    private static final String GIT_BASH_REQUIRED_MESSAGE = "Git Bash is required on Windows to run the bash tool. "
            + "Install Git for Windows from https://git-scm.com/download/win and make sure bash.exe is available.";

    private final ObjectMapper objectMapper;
    private final WorkspacePathResolver pathResolver;

    public BashTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
    }

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return "Execute a shell command in the workspace and return exit code, stdout, and stderr.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("command", ToolSchemas.stringProperty(objectMapper, "Shell command to execute."));
        properties.set("cwd", ToolSchemas.stringProperty(objectMapper, "Workspace-relative working directory. Default ."));
        properties.set("timeoutSeconds", ToolSchemas.integerProperty(objectMapper, "Timeout in seconds. Default 60."));
        return ToolSchemas.objectSchema(objectMapper, properties, "command");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        String command = ToolArguments.requiredText(arguments, "command");
        Path cwd = pathResolver.resolve(context, arguments.path("cwd").asText("."));
        if (!Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("Working directory not found: " + pathResolver.relative(context, cwd));
        }
        int timeoutSeconds = arguments.path("timeoutSeconds").asInt(60);
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }

        List<String> shellCommand = shellCommand(command);
        ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
        processBuilder.directory(cwd.toFile());
        Process process = processBuilder.start();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> stdout = executor.submit(streamReader(process.getInputStream()));
        Future<String> stderr = executor.submit(streamReader(process.getErrorStream()));

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        int exitCode;
        if (finished) {
            exitCode = process.exitValue();
        } else {
            process.destroyForcibly();
            exitCode = -1;
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("command", command);
        result.put("cwd", pathResolver.relative(context, cwd));
        result.put("exitCode", exitCode);
        result.put("timedOut", !finished);
        result.put("stdout", futureValue(stdout));
        result.put("stderr", futureValue(stderr));
        executor.shutdownNow();
        return ToolExecutionResult.of(result.toString());
    }

    private Callable<String> streamReader(final InputStream inputStream) {
        return () -> {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        };
    }

    private List<String> shellCommand(String command) {
        return shellCommand(command, System.getProperty("os.name"), System.getenv());
    }

    static List<String> shellCommand(String command, String osName, Map<String, String> environment) {
        if (isWindows(osName)) {
            String gitBash = findGitBash(environment, osName);
            if (gitBash == null) {
                throw new IllegalStateException(GIT_BASH_REQUIRED_MESSAGE);
            }
            return Arrays.asList(gitBash, "-lc", command);
        }
        return Arrays.asList("/bin/sh", "-lc", command);
    }

    static String findGitBash(Map<String, String> environment, String osName) {
        List<Path> candidates = new ArrayList<>();
        addGitInstallCandidates(candidates, environment, "ProgramFiles");
        addGitInstallCandidates(candidates, environment, "ProgramFiles(x86)");
        addLocalAppDataCandidates(candidates, environment);
        addPathCandidates(candidates, envValue(environment, "PATH"), pathSeparator(osName));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static void addGitInstallCandidates(List<Path> candidates,
                                                Map<String, String> environment,
                                                String envName) {
        String root = envValue(environment, envName);
        if (root == null || root.trim().isEmpty()) {
            return;
        }
        candidates.add(Paths.get(root, "Git", "bin", "bash.exe"));
        candidates.add(Paths.get(root, "Git", "usr", "bin", "bash.exe"));
    }

    private static void addLocalAppDataCandidates(List<Path> candidates, Map<String, String> environment) {
        String localAppData = envValue(environment, "LOCALAPPDATA");
        if (localAppData == null || localAppData.trim().isEmpty()) {
            return;
        }
        candidates.add(Paths.get(localAppData, "Programs", "Git", "bin", "bash.exe"));
        candidates.add(Paths.get(localAppData, "Programs", "Git", "usr", "bin", "bash.exe"));
    }

    private static void addPathCandidates(List<Path> candidates, String pathValue, String separator) {
        if (pathValue == null || pathValue.trim().isEmpty()) {
            return;
        }
        String[] entries = pathValue.split(separator);
        for (String entry : entries) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            Path candidate = Paths.get(entry).resolve("bash.exe");
            if (looksLikeGitBash(candidate)) {
                candidates.add(candidate);
            }
        }
    }

    private static boolean looksLikeGitBash(Path candidate) {
        String value = candidate.toString().toLowerCase(Locale.ENGLISH);
        return value.contains("\\git\\") || value.contains("/git/");
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ENGLISH).contains("win");
    }

    private static String pathSeparator(String osName) {
        return isWindows(osName) ? ";" : File.pathSeparator;
    }

    private static String envValue(Map<String, String> environment, String name) {
        if (environment == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String futureValue(Future<String> future) {
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }
}
