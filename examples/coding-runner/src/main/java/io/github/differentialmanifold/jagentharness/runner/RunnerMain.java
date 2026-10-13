package io.github.differentialmanifold.jagentharness.runner;

import io.github.differentialmanifold.jagentharness.client.AgentRunner;
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
                if (Arrays.asList("help", "full-access").contains(name)) flags.put(name, "true");
                else if (Arrays.asList("home", "workspace", "name", "session").contains(name)
                        && i + 1 < args.length) flags.put(name, args[++i]);
                else throw new IllegalArgumentException("Unknown or incomplete option: --" + name);
            } else words.add(args[i]);
        }
        if (words.isEmpty() || flags.containsKey("help")) {
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
        String command = words.get(0);
        if ("connect".equals(command)) {
            if (words.size() != 2)
                throw new IllegalArgumentException("connect requires a server URL");
            AgentRunner.connect(home, words.get(1), token);
            System.out.println("Connected to " + words.get(1));
            return;
        }
        if (words.size() != 1 || !("serve".equals(command) || "recover".equals(command)))
            throw new IllegalArgumentException("Use connect, serve or recover");
        Path workspace =
                Paths.get(flags.getOrDefault("workspace", System.getProperty("user.dir")))
                        .toRealPath();
        CodingTools coding = new CodingTools();
        try (AgentRunner runner =
                AgentRunner.builder(home)
                        .token(token)
                        .name(flags.getOrDefault("name", InetAddress.getLocalHost().getHostName()))
                        .workspace(workspace.toString())
                        .tools(coding.tools(workspace, flags.containsKey("full-access")))
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
                    if (!flags.containsKey("session"))
                        throw new IllegalArgumentException("recover requires --session");
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
                        + "  connect <https://server>\n"
                        + "  serve --workspace <path> [--name <name>] [--full-access]\n"
                        + "  recover --session <id> --workspace <path> [--full-access]\n"
                        + "Use --home <path> for another runner identity. Authentication: JAGENT_CLIENT_TOKEN.\n"
                        + "Coding defaults to read/search tools. --full-access enables writes and shell execution under the runner OS account.");
    }
}
