---
name: runner-setup
description: Set up, stop, restart, or troubleshoot a Runner on an SSH machine connected to this Harness, including cleanup of a temporary execution environment. Also use for an explicitly requested additional Runner process, not ordinary project work or creating a role on an existing Runner.
---

# Prepare a Runner environment

The Agent lives in this Harness; the Runner executes tools on the target machine. Use a Project on your current Runner for inspection, transfers and SSH commands. Pass the applicable instructions below to that Project: it does not inherit this skill.

## Reuse the right environment

Use `agents discover` to find existing Agents and registered Runners, including capabilities, workspace roots and online status. Reuse a suitable Agent before creating one. A new Agent is a separate persistent role or memory; omitting `runnerId` shares your own Runner. A new repository needs a Project; another task in that repository usually needs a session.

Deploy another Runner for another machine, tool set, OS account/container, independently managed process, or an explicit request. Creating an Agent does not install software or create a Runner process.

## Plan cleanup when deploying

Treat a new, dedicated Runner for a bounded task as temporary. Keep existing shared Runners and explicitly long-term environments running. Pausing an Agent or completing one conversation does not mean its Runner is unused.

Record a short note in the controlling Project: target host, Runner ID, Harness URL and state root, service name, log location, start/stop commands, and any tunnel's owner. Do not record credential values. Add cleanup to the owning task so it is followed through after acceptance. Use the existing OS service manager; no additional lifecycle service is needed.

## Find the current connection and installation

Ask the Project to identify its actual running Runner process and state directory. The CLI's `--server` overrides `JAGENT_SERVER_URL`; the default is `http://127.0.0.1:18181`. The Runner's `config.json` also records `server` and `runnerId`. Match the ID to `currentRunnerId` from discovery. Read only these fields with a JSON parser; the same file contains a private Runner secret. Do not print whole configuration files, process environments, credentials or expanded authorization headers.

This address works from the current Runner, not necessarily from the target. The SSH destination is a machine address, not the Harness URL. Do not assume you can read the Harness host's configuration or that its bind address is remotely reachable.

## Obtain the package inside this environment

The Harness can serve operator-installed packages at these paths, relative to its base URL:

- `/api/v1/distributions/runners/coding/manifest.json`
- `/api/v1/distributions/runners/coding/runner.jar`

The manifest contains `kind`, `version`, `artifact`, `sha256` and `javaVersion`. Use the existing Runner's configured `JAGENT_CLIENT_TOKEN` for Bearer authentication when required, without placing its value in chat or logs. Missing credentials are a prerequisite to resolve, not a reason to disable authentication.

Download the manifest and JAR through the current Runner. Check the expected kind and artifact, verify SHA-256, and check the target's Java version. SCP both files to the target if it cannot fetch them directly; verify the checksum there before starting. The JAR includes the SDK and coding tools, but not Java itself.

A 404 means the package has not been installed on this Harness. Inspect the current Coding Runner's installation for a compatible offline package or reuse its actual working JAR, recording its version and SHA-256 and verifying the transferred bytes. Do not assume a source checkout, Maven, GitHub or public internet access exists. A 503 means the published package failed verification; have that package corrected or use a verified local installation. Never invent download URLs or silently switch to an arbitrary latest release.

For operators with a source checkout, `mvn -pl examples/coding-runner -am package` produces `examples/coding-runner/target/runners/coding/{runner.jar,manifest.json}` and an offline ZIP. Copy the `coding` directory into the Harness's configured distribution directory, normally `data/runners/`. This is a preparation step, not something required on every new machine. An isolated network also needs a suitable Java runtime available locally; report that blocker if it is missing.

## Establish a usable network path

Test connectivity from the target, including an authenticated API request when applicable.

1. If the target can reach a configured Harness address directly, use it.
2. If it cannot, but the current Runner can reach the Harness and SSH into the target, establish SSH remote port forwarding from the current Runner's machine. Bind the forwarded port only to the target's loopback interface, choose an unused port and check forwarding actually succeeded.
3. If neither route exists, report the missing network or SSH access. Do not change server exposure or authentication to bypass it.

For example, when the Harness is HTTP on the SSH initiator's `127.0.0.1:18181`, a forward with `-R 127.0.0.1:18182:127.0.0.1:18181` makes it reachable at `http://127.0.0.1:18182` on the target. Use the real addresses and free ports, with `-N -T -o ExitOnForwardFailure=yes`. For HTTPS preserve certificate hostname validation; do not work around a tunnel hostname mismatch by disabling TLS verification.

The SSH initiator maintains the tunnel. Supervise and reconnect it there; manage the Runner separately on the target, according to whether the environment is temporary or long-term. A detached process or `nohup` survives a shell exit but does not provide reboot recovery. Starting a service on the target does not maintain an SSH tunnel initiated on another machine.

## Start and verify

For the Coding Runner, the basic command is:

```sh
java -jar runner.jar --server <address-reachable-from-target> --name <name>
```

`serve` is optional; `recover` is not a second startup step. Supply the Harness token in the Runner's environment if required. Use `--description` to describe this machine's capabilities. Keep credentials out of command text, output and persistent Agent memory.

`--home` selects persistent identity and execution state, not a workspace. Concurrent Runner processes against the same Harness need distinct state directories. Do not copy `config.json` or clone identities. A state lock usually means an existing process should be reused. Workspace conflicts require waiting or a separate checkout; ownership or permission errors require the authorized owner or correct access. Creating another identity does not resolve these conflicts.

Require the Project to return machine identity, Runner ID, package version/checksum, non-secret startup logs, and how both the Runner and any tunnel are kept running. Use `agents discover` again to match the expected online Runner to that evidence. Only then create or reuse an Agent bound to its registered ID and verify a small task through it. Your original Agent stays on its own Runner. A successful start command or an acknowledgement alone is not completion; report any remaining limitation accurately.

## Stop and reuse a temporary environment

After the whole task is verified, use a Project on the controlling Runner to check that the dedicated Runner has no unfinished work, background processes that still need it, or other Agent/Project consumers. Then stop its recorded service through SSH and verify that it stopped. Stop a tunnel only if it belongs exclusively to this deployment. Normal service stop cancels active runs; finish them first. If usage is uncertain, leave the Runner running until it is resolved.

Keep the Agent, identity, journal and project files. For later work, restore any tunnel and start the same service with the same Harness URL and state root. Verify the original Runner is online before delegating to the existing Agent. Use the recorded SSH path from another running terminal; a stopped Runner cannot restart itself through chat. Do not create a replacement Agent or Runner identity merely because it is offline.
