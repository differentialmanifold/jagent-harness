package com.example.serverdemo;

import io.github.differentialmanifold.jagentharness.core.identity.*;
import io.github.differentialmanifold.jagentharness.store.jdbc.*;
import java.nio.file.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Local administration only. Does not start Agent loops or expose a management HTTP API. */
final class UserCommand {
    static void run(String[] args) {
        if (args.length < 2)
            throw new IllegalArgumentException(
                    "users create --name <name> --output <file> | users list | users reset --id <id> --output <file> | users revoke --id <id> | users link --id <id> --console <url> --output <file>");
        Map<String, String> flags = new HashMap<>();
        for (int i = 2; i < args.length; i += 2) {
            if (i + 1 >= args.length
                    || !Arrays.asList("--name", "--id", "--output", "--console").contains(args[i]))
                throw new IllegalArgumentException("Invalid user command option");
            flags.put(args[i], args[i + 1]);
        }
        if (!Arrays.asList("create", "list", "reset", "revoke", "link").contains(args[1]))
            throw new IllegalArgumentException("Unknown user command");
        if (Arrays.asList("create", "reset", "link").contains(args[1])
                && !flags.containsKey("--output"))
            throw new IllegalArgumentException(
                    "--output is required; credentials are never printed to logs");
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(ServerDemoApplication.class)
                        .web(WebApplicationType.NONE)
                        .run("--harness.enabled=false")) {
            DataSource source = context.getBean(DataSource.class);
            JdbcStoreProperties props =
                    Binder.get(context.getEnvironment())
                            .bind("harness.store.jdbc", JdbcStoreProperties.class)
                            .orElseGet(JdbcStoreProperties::new);
            new JdbcSchemaInitializer(source, props, context).initialize();
            UserStore users = new JdbcUserStore(new JdbcTemplate(source), props);
            String id = flags.get("--id");
            if ("link".equals(args[1])) {
                java.net.URI url =
                        java.net.URI.create(
                                Objects.requireNonNull(
                                        flags.get("--console"), "--console is required"));
                if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme()))
                        || url.getHost() == null
                        || url.getUserInfo() != null
                        || url.getQuery() != null
                        || url.getFragment() != null
                        || !(url.getPath().isEmpty() || "/".equals(url.getPath())))
                    throw new IllegalArgumentException("--console must be a plain http(s) origin");
                UserStore.Access ticket = users.createTicket(id, null);
                Path out = Paths.get(flags.get("--output"));
                UserTokenFiles.writePrivate(
                        out,
                        url.toString().replaceAll("/$", "")
                                + "/#jagent-ticket="
                                + ticket.secret
                                + "\n");
                System.out.println(
                        "Single-use Console link saved to "
                                + out.toAbsolutePath()
                                + "; expires in 60 seconds");
                return;
            }
            if ("list".equals(args[1])) {
                for (UserStore.User u : users.list())
                    System.out.println(
                            u.userId + "  " + u.name + "  " + (u.active ? "active" : "revoked"));
                return;
            }
            if ("create".equals(args[1])) id = users.create(flags.get("--name")).userId;
            if ("revoke".equals(args[1])) users.revoke(id);
            else {
                Path out = Paths.get(flags.get("--output"));
                UserTokenFiles.write(out, id, users.issueToken(id));
                System.out.println("User " + id + " credentials saved to " + out.toAbsolutePath());
            }
        } catch (Exception e) {
            throw new IllegalStateException("User command failed: " + e.getMessage(), e);
        }
    }
}
