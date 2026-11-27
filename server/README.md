# Harness server

The standalone Spring Boot service runs Agent and Project sessions, stores conversations and Skills, and dispatches tool calls to registered Runners. The Console is deployed separately.

## Run

Download the server JAR for your release. Set `JAGENT_MODEL`, `JAGENT_OPENAI_BASE_URL` and your provider's `JAGENT_OPENAI_API_KEY` in the service environment. Start it from a persistent working directory with Java 8 or later.

```sh
java -jar jagent-server-2.4.0.jar
```

The default listener is `127.0.0.1:18181`. The service initializes SQLite in `agent-server.db` and writes the first user credential to `data/user.env`. Use the same working directory and database settings for the local `users` commands. [PostgreSQL and configuration](../QUICK_START.md#choose-a-database) · [User credentials and remote access](../docs/guides/access.md)

## Build from a checkout

```sh
mvn -pl server -am package
java -jar server/target/jagent-server-2.4.0.jar
```

Run these commands from the repository root with a JDK, Maven and Node.js. Packaging embeds the product source, tests and guides from this build in the JAR. A connected Coding Runner retrieves this archive over the authenticated API and verifies its SHA-256 before reporting the local directory to mainbot. The snapshot includes `console/src/`. The separately deployed Console identifies its own build at `/version.json`.

[Server modules and extensions](../jagent-harness-sdk/README.md) · [Console hosting](../console/README.md) · [Quick start](../QUICK_START.md)
