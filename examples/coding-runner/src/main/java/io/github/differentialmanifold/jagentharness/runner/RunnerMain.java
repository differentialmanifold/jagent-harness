package io.github.differentialmanifold.jagentharness.runner;

import io.github.differentialmanifold.jagentharness.client.AgentRunner;
import io.github.differentialmanifold.jagentharness.client.LocalWorkspaces;
import io.github.differentialmanifold.jagentharness.client.RunnerConsole;
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
                if (Arrays.asList("help", "no-browser", "link").contains(name))
                    flags.put(name, "true");
                else if (Arrays.asList(
                                        "profile",
                                        "home",
                                        "server",
                                        "name",
                                        "session",
                                        "description",
                                        "credentials",
                                        "console",
                                        "idle-disconnect-timeout")
                                .contains(name)
                        && i + 1 < args.length) flags.put(name, args[++i]);
                else throw new IllegalArgumentException("Unknown or incomplete option: --" + name);
            } else words.add(args[i]);
        }
        if (flags.containsKey("help")) {
            help();
            return;
        }
        String command = words.isEmpty() || "runner".equals(words.get(0)) ? "serve" : words.get(0);
        boolean pair = "pair".equals(command), open = "open".equals(command);
        if (!Arrays.asList("serve", "recover", "open", "pair").contains(command)
                || (pair ? words.size() != 2 : words.size() > 1))
            throw new IllegalArgumentException("Use serve, recover, open or pair <code>");
        if (pair && !words.get(1).matches("(?i)[a-z2-9]{4}-?[a-z2-9]{4}"))
            throw new IllegalArgumentException("Enter the pairing code shown in your browser");
        if ("recover".equals(command) != flags.containsKey("session"))
            throw new IllegalArgumentException("recover requires --session <id>");
        if (flags.containsKey("link") && !open)
            throw new IllegalArgumentException("--link requires open");

        Map<String, String> env = System.getenv();
        Path state =
                Paths.get(
                        env.getOrDefault(
                                "JAGENT_STATE_DIR", System.getProperty("user.home") + "/.jagent"));
        RunnerProfiles profiles = new RunnerProfiles(state);
        String selectedServer = flags.getOrDefault("server", env.get("JAGENT_SERVER_URL"));
        if (selectedServer != null) selectedServer = selectedServer.replaceAll("/$", "");
        String selectedHome = flags.getOrDefault("home", env.get("JAGENT_RUNNER_HOME"));
        boolean reuse =
                !words.isEmpty()
                        && "runner".equals(words.get(0))
                        && !flags.containsKey("credentials")
                        && !env.containsKey("JAGENT_USER_TOKEN")
                        && !env.containsKey("JAGENT_USER_CREDENTIALS");
        Properties profile =
                open || pair || reuse || flags.containsKey("profile")
                        ? profiles.select(flags.get("profile"), selectedServer, selectedHome)
                        : new Properties();
        String server =
                selectedServer != null
                        ? selectedServer
                        : profile.getProperty("server", "http://127.0.0.1:18181");
        Path home =
                Paths.get(
                        selectedHome != null
                                ? selectedHome
                                : profile.getProperty(
                                        "home",
                                        System.getProperty("user.home") + "/.jagent-runner"));
        String consoleUrl =
                flags.getOrDefault(
                        "console",
                        profile.getProperty(
                                "console",
                                env.getOrDefault(
                                        "JAGENT_CONSOLE_URL",
                                        server.equals("http://127.0.0.1:18181")
                                                ? "http://127.0.0.1:5175"
                                                : server)));
        String reference =
                flags.getOrDefault(
                        "credentials",
                        profile.getProperty("credentials", env.get("JAGENT_USER_CREDENTIALS")));
        Path credentials =
                Paths.get(
                        reference == null
                                ? (env.containsKey("JAGENT_STATE_DIR")
                                        ? state.resolve("data/user.env").toString()
                                        : "data/user.env")
                                : reference);
        String fileToken = "";
        // An unqualified local bootstrap file belongs to the loopback Harness.
        java.net.URI origin = java.net.URI.create(server);
        boolean local = Arrays.asList("127.0.0.1", "localhost", "[::1]").contains(origin.getHost());
        boolean directJar = !env.containsKey("JAGENT_STATE_DIR") && !open && !pair;
        if (Files.isRegularFile(credentials)
                && (reference != null || profile.isEmpty() && (local || directJar))) {
            Properties props = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(credentials)) {
                props.load(reader);
            }
            fileToken = props.getProperty("JAGENT_USER_TOKEN", "");
        }
        String token = env.getOrDefault("JAGENT_USER_TOKEN", "");
        boolean explicitCredential =
                flags.containsKey("credentials") || profile.containsKey("credentials");
        if (explicitCredential || token.isEmpty()) token = fileToken;
        if (token.isEmpty())
            throw new IllegalArgumentException(
                    "Supply the Harness-issued credential via --credentials <file> or JAGENT_USER_TOKEN");
        Path credentialReference = token.equals(fileToken) ? credentials : null;

        if (open || pair) {
            if ("http".equals(origin.getScheme()) && !local)
                System.err.println(
                        "Plain HTTP: credentials are sent unencrypted. Use a trusted network or HTTPS.");
            RunnerConsole connection =
                    new RunnerConsole(server, home, token, profile.getProperty("user"));
            if (open) {
                String link = connection.link(consoleUrl);
                if (flags.containsKey("link")) System.out.println(link);
                else openBrowser(link);
            } else {
                String code = words.get(1);
                String browser = connection.pairingBrowser(code);
                System.out.printf(
                        "Allow %s showing code %s to access user %s on %s via Runner %s?%n",
                        browser, code, connection.userId, server, connection.runnerId);
                java.io.Console terminal = System.console();
                if (terminal == null)
                    throw new IllegalArgumentException(
                            "Confirm pairing in an interactive terminal on the Runner machine");
                if ("yes".equalsIgnoreCase(terminal.readLine("Type yes to approve: "))) {
                    connection.approve(code);
                    System.out.println("Approved. Return to the requesting browser.");
                } else System.out.println("Pairing cancelled.");
            }
            return;
        }
        CodingTools coding = new CodingTools();
        Map<String, String> diagnostics = coding.toolDiagnostics();
        RunnerBuild build = new RunnerBuild();

        System.out.println("Search backend (find, grep): " + diagnostics.get("grep"));
        try (AgentRunner runner =
                AgentRunner.builder(server, home)
                        .version(build.value("version"))
                        .buildRevision(build.value("buildRevision"))
                        .userToken(token, profile.getProperty("user"))
                        .idleDisconnectTimeout(
                                java.time.Duration.ofSeconds(
                                        Long.parseLong(
                                                flags.getOrDefault(
                                                        "idle-disconnect-timeout",
                                                        System.getenv()
                                                                .getOrDefault(
                                                                        "JAGENT_RUNNER_IDLE_DISCONNECT_TIMEOUT_SECONDS",
                                                                        "86400")))))
                        .onRegistered(
                                user -> {
                                    try {
                                        profiles.remember(
                                                server,
                                                consoleUrl,
                                                home,
                                                user,
                                                credentialReference);
                                    } catch (java.io.IOException e) {
                                        System.err.println(
                                                "Could not save Runner profile: " + e.getMessage());
                                    }
                                })
                        .onConsole(
                                consoleUrl,
                                flags.containsKey("no-browser")
                                                || "false"
                                                        .equalsIgnoreCase(
                                                                System.getenv(
                                                                        "JAGENT_OPEN_BROWSER"))
                                        ? null
                                        : RunnerMain::openBrowser)
                        .name(
                                flags.getOrDefault(
                                        "name",
                                        System.getenv()
                                                .getOrDefault(
                                                        "JAGENT_RUNNER_NAME",
                                                        InetAddress.getLocalHost().getHostName())))
                        .description(
                                flags.getOrDefault(
                                        "description",
                                        System.getenv()
                                                .getOrDefault(
                                                        "JAGENT_RUNNER_DESCRIPTION",
                                                        "Coding environment with local file, search and shell tools. Installed SDKs must be verified before use.")))
                        .capabilities(Collections.singletonList("coding"))
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
            System.out.println("Runner state: " + runner.stateDirectory());
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

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            Process opener;
            if (os.contains("mac")) opener = new ProcessBuilder("open", url).start();
            else if (os.contains("win"))
                opener = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else opener = new ProcessBuilder("xdg-open", url).start();
            if (opener.waitFor(750, java.util.concurrent.TimeUnit.MILLISECONDS)
                    && opener.exitValue() != 0)
                throw new java.io.IOException("Browser launcher failed");
            System.out.println("Opening your Console in the default browser");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not launch a browser. Run jagent open --link on this machine and paste the link in your browser.",
                    e);
        }
    }

    private static void help() {
        System.out.println(
                "JAgent Coding Runner (Java 8+)\n"
                        + "Run without arguments to start the runner at http://127.0.0.1:18181.\n"
                        + "  [serve] [--server <url>] [--name <name>] [--description <text>] [--home <path>]\n"
                        + "  open [--link] [--profile <id>]   Open a fresh Console session or print its link.\n"
                        + "  pair <code> [--profile <id>]     Approve a browser on this Runner machine.\n"
                        + "Optional recovery of an interrupted operation (not a startup step):\n"
                        + "  recover --session <id> [--server <url>] [--home <path>]\n"
                        + "JAGENT_SERVER_URL overrides the default server address. --console <url> selects the remote Console; --no-browser skips opening it. --help prints this help.\n"
                        + "State is isolated by server and user under --home <path>. Authentication: JAGENT_USER_TOKEN or --credentials <user.env>.\n"
                        + "--idle-disconnect-timeout <seconds> exits after consecutive idle disconnection (default 86400; 0 keeps reconnecting). Environment: JAGENT_RUNNER_IDLE_DISCONNECT_TIMEOUT_SECONDS.\n"
                        + "Select the workspace and enabled tools per project in the console. Selected tools run with full access under the runner OS account.");
    }
}
