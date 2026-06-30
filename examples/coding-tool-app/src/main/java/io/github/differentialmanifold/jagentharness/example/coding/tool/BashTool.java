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
import java.util.Collections;
import java.util.LinkedHashMap;
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
import io.github.differentialmanifold.jagentharness.core.agent.StopRegistration;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalMode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class BashTool implements ToolDefinition {

    private static final String GIT_BASH_REQUIRED_MESSAGE = "Git Bash is required on Windows to run the bash tool. "
            + "Install Git for Windows from https://git-scm.com/download/win and make sure bash.exe is available.";
    private static final List<String> COMMAND_SEPARATORS = Arrays.asList(";", "&&", "||", "|");
    private static final List<String> MUTATING_COMMANDS = Arrays.asList(
            "rm", "rmdir", "mv", "touch", "mkdir", "truncate", "chmod", "chown", "chgrp",
            "sed", "tee", "dd", "install", "ln", "cp", "rsync");
    private static final List<String> OUTSIDE_CWD_MUTATION_COMMANDS = Arrays.asList(
            "rm", "rmdir", "mv", "touch", "mkdir", "truncate", "chmod", "chown", "chgrp",
            "sed", "tee", "dd", "install", "ln", "cp", "rsync", "make", "npm", "pnpm",
            "yarn", "mvn", "gradle", "cargo", "go", "python", "python3", "node", "ruby",
            "perl", "sh", "bash", "zsh");

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
        return "Execute a shell command and return exit code, stdout, and stderr. Relative cwd resolves from the workspace; absolute cwd is allowed. Prefer dedicated tools first when they directly cover finding, reading, searching, or editing files.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("command", ToolSchemas.stringProperty(objectMapper, "Shell command to execute."));
        properties.set("cwd", ToolSchemas.stringProperty(objectMapper, "Workspace-relative or absolute working directory. Default ."));
        properties.set("timeoutSeconds", ToolSchemas.integerProperty(objectMapper, "Timeout in seconds. Default 60."));
        return ToolSchemas.objectSchema(objectMapper, properties, "command");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        StopSignal stopSignal = context.getStopSignal();
        stopSignal.throwIfAborted();
        String command = ToolArguments.requiredText(arguments, "command");
        Path cwd = pathResolver.resolve(context, arguments.path("cwd").asText("."));
        if (!Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("Working directory not found: " + pathResolver.relative(context, cwd));
        }
        int timeoutSeconds = arguments.path("timeoutSeconds").asInt(60);
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }
        requireApprovalIfMayMutateOutsideWorkspace(context, command, cwd);

        List<String> shellCommand = shellCommand(command);
        ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
        processBuilder.directory(cwd.toFile());
        Process process = processBuilder.start();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> stdout = executor.submit(streamReader(process.getInputStream()));
        Future<String> stderr = executor.submit(streamReader(process.getErrorStream()));

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
            stopSignal.throwIfAborted();

            int exitCode;
            if (finished) {
                exitCode = process.exitValue();
            } else {
                destroyProcess(process);
                exitCode = -1;
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("command", command);
            result.put("cwd", pathResolver.relative(context, cwd));
            result.put("exitCode", exitCode);
            result.put("timedOut", !finished);
            result.put("stdout", futureValue(stdout));
            result.put("stderr", futureValue(stderr));
            return ToolExecutionResult.of(result.toString());
        } finally {
            executor.shutdownNow();
        }
    }

    private void requireApprovalIfMayMutateOutsideWorkspace(ToolContext context,
                                                            String command,
                                                            Path cwd) throws Exception {
        if (context.getApprovalMode() == ToolApprovalMode.FULL_ACCESS) {
            return;
        }
        BashMutation mutation = findOutsideWorkspaceMutation(context, command, cwd);
        if (mutation == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("command", command);
        metadata.put("cwd", cwd.toAbsolutePath().normalize().toString());
        metadata.put("reason", mutation.reason);
        context.requestApproval(new ToolApprovalRequest(
                null,
                null,
                null,
                "Approve bash outside workspace",
                "The bash tool may modify files outside the current workspace.",
                "bash",
                mutation.target,
                metadata));
    }

    private BashMutation findOutsideWorkspaceMutation(ToolContext context, String command, Path cwd) {
        List<String> tokens = shellTokens(command);
        BashMutation commandMutation = findOutsideMutatingCommand(context, tokens, cwd);
        if (commandMutation != null) {
            return commandMutation;
        }
        if (!pathResolver.isInsideWorkspace(context, cwd) && commandMayMutate(tokens)) {
            return new BashMutation(
                    cwd.toAbsolutePath().normalize().toString(),
                    "bash cwd is outside workspace");
        }
        return null;
    }

    private BashMutation findOutsideMutatingCommand(ToolContext context, List<String> tokens, Path cwd) {
        int start = 0;
        while (start < tokens.size()) {
            int end = start;
            while (end < tokens.size() && !isCommandSeparator(tokens.get(end))) {
                end++;
            }
            BashMutation mutation = inspectCommandSegment(context, tokens, start, end, cwd);
            if (mutation != null) {
                return mutation;
            }
            start = end + 1;
        }
        return null;
    }

    private BashMutation inspectCommandSegment(ToolContext context,
                                               List<String> tokens,
                                               int start,
                                               int end,
                                               Path cwd) {
        int commandIndex = firstCommandIndex(tokens, start, end);
        if (commandIndex < 0) {
            return null;
        }
        String commandName = commandName(tokens.get(commandIndex));
        if (isShellCommand(commandName)) {
            String nested = nestedShellCommand(tokens, commandIndex + 1, end);
            if (nested != null) {
                return findOutsideWorkspaceMutation(context, nested, cwd);
            }
        }
        if (!MUTATING_COMMANDS.contains(commandName)) {
            return null;
        }
        List<String> pathTokens = mutatingPathTokens(commandName, tokens, commandIndex + 1, end);
        for (String pathToken : pathTokens) {
            Path path = resolveShellPath(cwd, pathToken);
            if (!pathResolver.isInsideWorkspace(context, path)) {
                return new BashMutation(
                        path.toAbsolutePath().normalize().toString(),
                        commandName + " targets a path outside workspace");
            }
        }
        return null;
    }

    private boolean commandMayMutate(List<String> tokens) {
        for (int start = 0; start < tokens.size(); ) {
            int end = start;
            while (end < tokens.size() && !isCommandSeparator(tokens.get(end))) {
                end++;
            }
            int commandIndex = firstCommandIndex(tokens, start, end);
            if (commandIndex >= 0
                    && OUTSIDE_CWD_MUTATION_COMMANDS.contains(commandName(tokens.get(commandIndex)))) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    private int firstCommandIndex(List<String> tokens, int start, int end) {
        int index = start;
        while (index < end) {
            String token = tokens.get(index);
            String commandName = commandName(token);
            if (token == null || token.trim().isEmpty() || isEnvironmentAssignment(token)) {
                index++;
                continue;
            }
            if ("sudo".equals(commandName) || "command".equals(commandName) || "builtin".equals(commandName)
                    || "nohup".equals(commandName) || "time".equals(commandName) || "nice".equals(commandName)) {
                index++;
                while (index < end && tokens.get(index).startsWith("-")) {
                    index++;
                }
                continue;
            }
            if ("env".equals(commandName)) {
                index++;
                while (index < end && (tokens.get(index).startsWith("-") || isEnvironmentAssignment(tokens.get(index)))) {
                    index++;
                }
                continue;
            }
            return index;
        }
        return -1;
    }

    private List<String> mutatingPathTokens(String commandName, List<String> tokens, int start, int end) {
        if ("dd".equals(commandName)) {
            return ddOutputTokens(tokens, start, end);
        }
        if ("cp".equals(commandName) || "ln".equals(commandName) || "rsync".equals(commandName)) {
            return destinationTokens(tokens, start, end);
        }
        if ("sed".equals(commandName) && !hasSedInPlaceOption(tokens, start, end)) {
            return Collections.emptyList();
        }
        return nonOptionOperands(tokens, start, end);
    }

    private List<String> ddOutputTokens(List<String> tokens, int start, int end) {
        List<String> result = new ArrayList<String>();
        for (int i = start; i < end; i++) {
            String token = tokens.get(i);
            if (token.startsWith("of=") && token.length() > 3) {
                result.add(token.substring(3));
            }
        }
        return result;
    }

    private List<String> destinationTokens(List<String> tokens, int start, int end) {
        List<String> operands = nonOptionOperands(tokens, start, end);
        if (operands.isEmpty()) {
            return operands;
        }
        return Collections.singletonList(operands.get(operands.size() - 1));
    }

    private List<String> nonOptionOperands(List<String> tokens, int start, int end) {
        List<String> operands = new ArrayList<String>();
        boolean parseOptions = true;
        for (int i = start; i < end; i++) {
            String token = tokens.get(i);
            if ("--".equals(token)) {
                parseOptions = false;
                continue;
            }
            if (parseOptions && token.startsWith("-") && token.length() > 1) {
                continue;
            }
            if (!isEnvironmentAssignment(token)) {
                operands.add(token);
            }
        }
        return operands;
    }

    private boolean hasSedInPlaceOption(List<String> tokens, int start, int end) {
        for (int i = start; i < end; i++) {
            String token = tokens.get(i);
            if ("-i".equals(token) || token.startsWith("-i.")) {
                return true;
            }
            if (token.startsWith("-") && token.indexOf('i') >= 0 && !token.startsWith("--")) {
                return true;
            }
        }
        return false;
    }

    private String nestedShellCommand(List<String> tokens, int start, int end) {
        for (int i = start; i < end; i++) {
            String token = tokens.get(i);
            if ("-c".equals(token) && i + 1 < end) {
                return tokens.get(i + 1);
            }
            if (!token.startsWith("-")) {
                return null;
            }
        }
        return null;
    }

    private List<String> shellTokens(String command) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                flushToken(tokens, current);
                continue;
            }
            if (c == ';') {
                flushToken(tokens, current);
                tokens.add(";");
                continue;
            }
            if ((c == '&' || c == '|') && i + 1 < command.length() && command.charAt(i + 1) == c) {
                flushToken(tokens, current);
                tokens.add(new String(new char[] {c, c}));
                i++;
                continue;
            }
            if (c == '|') {
                flushToken(tokens, current);
                tokens.add("|");
                continue;
            }
            current.append(c);
        }
        flushToken(tokens, current);
        return tokens;
    }

    private void flushToken(List<String> tokens, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        tokens.add(current.toString());
        current.setLength(0);
    }

    private Path resolveShellPath(Path cwd, String token) {
        String value = stripTrailingCommandPunctuation(token == null ? "" : token.trim());
        if (value.startsWith("~/") || "~".equals(value)) {
            String home = System.getProperty("user.home");
            value = "~".equals(value) ? home : home + value.substring(1);
        }
        Path path = Paths.get(value);
        return path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : cwd.resolve(path).toAbsolutePath().normalize();
    }

    private String stripTrailingCommandPunctuation(String value) {
        while (value.endsWith(")") || value.endsWith(";")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private boolean isEnvironmentAssignment(String token) {
        if (token == null) {
            return false;
        }
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
        String name = token.substring(0, equals);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private boolean isCommandSeparator(String token) {
        return COMMAND_SEPARATORS.contains(token);
    }

    private boolean isShellCommand(String commandName) {
        return "sh".equals(commandName)
                || "bash".equals(commandName)
                || "zsh".equals(commandName);
    }

    private String commandName(String token) {
        if (token == null) {
            return "";
        }
        String value = token.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < value.length()) {
            value = value.substring(slash + 1);
        }
        if (value.toLowerCase(Locale.ENGLISH).endsWith(".exe")) {
            value = value.substring(0, value.length() - 4);
        }
        return value.toLowerCase(Locale.ENGLISH);
    }

    private static class BashMutation {
        private final String target;
        private final String reason;

        private BashMutation(String target, String reason) {
            this.target = target;
            this.reason = reason;
        }
    }

    private void destroyProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
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
