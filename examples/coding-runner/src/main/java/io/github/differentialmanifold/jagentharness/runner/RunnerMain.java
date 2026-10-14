package io.github.differentialmanifold.jagentharness.runner;

import io.github.differentialmanifold.jagentharness.client.AgentRunner;
import io.github.differentialmanifold.jagentharness.client.LocalWorkspaces;
import io.github.differentialmanifold.jagentharness.protocol.ChatResponse;
import io.github.differentialmanifold.jagentharness.tools.coding.CodingTools;
import java.net.InetAddress;
import java.nio.file.*;
import java.util.*;

/** Coding-specific CLI; registration, polling and recovery belong to the client SDK. */
public final class RunnerMain {
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("Runner: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        List<String> words = new ArrayList<>();
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String name = args[i].substring(2);
                if ("help".equals(name)) flags.put(name, "true");
                else if (Arrays.asList("home", "server", "name", "session").contains(name)
                        && i + 1 < args.length) flags.put(name, args[++i]);
                else throw new IllegalArgumentException("Unknown or incomplete option: --" + name);
            } else words.add(args[i]);
        }
        if (flags.containsKey("help")) {
            help();
            return;
        }
        Path home =
                Paths.get(
                        flags.getOrDefault(
                                "home",
                                System.getenv()
                                        .getOrDefault(
                                                "JAGENT_RUNNER_HOME",
                                                System.getProperty("user.home")
                                                        + "/.jagent-runner")));
        String token = System.getenv().getOrDefault("JAGENT_CLIENT_TOKEN", "");
        String command = words.isEmpty() ? "serve" : words.get(0);
        if (words.size() > 1 || !("serve".equals(command) || "recover".equals(command)))
            throw new IllegalArgumentException("Use serve or recover");
        if ("recover".equals(command) && !flags.containsKey("session"))
            throw new IllegalArgumentException("recover requires --session");
        if (!"recover".equals(command) && flags.containsKey("session"))
            throw new IllegalArgumentException("--session is only used with recover");
        String server =
                flags.getOrDefault(
                        "server",
                        System.getenv()
                                .getOrDefault("JAGENT_SERVER_URL", "http://127.0.0.1:18181"));
        CodingTools coding = new CodingTools();
        Map<String, String> diagnostics = coding.toolDiagnostics();
        System.out.println("Search backend (find, grep): " + diagnostics.get("grep"));
        try (AgentRunner runner =
                AgentRunner.builder(server, home)
                        .token(token)
                        .name(
                                flags.getOrDefault(
                                        "name",
                                        System.getenv()
                                                .getOrDefault(
                                                        "JAGENT_RUNNER_NAME",
                                                        InetAddress.getLocalHost().getHostName())))
                        .workspaces(new LocalWorkspaces())
                        .tools(coding.tools())
                        .toolDiagnostics(diagnostics)
                        .instructions(coding.instructions())
                        .onStatus(System.out::println)
                        .onEvent(
                                event -> {
                                    if ("tool_execution_start".equals(event.type))
                                        System.out.println(
                                                "Executing " + event.payload.get("toolName"));
                                })
                        .build()) {
            Thread shutdown = new Thread(runner::close);
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                if ("serve".equals(command)) runner.serve();
                else {
                    ChatResponse response = runner.recover(flags.get("session"));
                    System.out.println(
                            response.status + ": " + Objects.toString(response.answer, ""));
                }
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    /* JVM shutdown is already in progress. */
                }
            }
        }
    }

    private static void help() {
        System.out.println(
                "JAgent Coding Runner (Java 8+)\n"
                        + "Run without arguments to start the runner at http://127.0.0.1:18181.\n"
                        + "  [serve] [--server <url>] [--name <name>] [--home <path>]\n"
                        + "Optional recovery of an interrupted operation (not a startup step):\n"
                        + "  recover --session <id> [--server <url>] [--home <path>]\n"
                        + "JAGENT_SERVER_URL overrides the default server address. --help prints this help.\n"
                        + "Use --home <path> for another runner identity. Authentication: JAGENT_CLIENT_TOKEN.\n"
                        + "Select the workspace and enabled tools per project in the console. Selected tools run with full access under the runner OS account.");
    }
}
