# Node.js Coding Runner

English · [简体中文](README.zh-CN.md)

Run Project tools on a machine with Node.js 20 (20.19 or later) or Node.js 22.12+. The Runner connects outbound to the Java Harness and registers seven tools: `read`, `ls`, `find`, `grep`, `write`, `edit` and `bash`. Coding Projects also discover the built-in browser skills. Windows requires Git for Windows to use `bash`. If ripgrep is available, `find` and `grep` use it. The Console shows the selected search backend.

## Install and connect

Once the package is published to your configured npm registry, run this on the machine that will execute tools:

```sh
npm install --global jagent
jagent runner --server https://harness.example.com --credentials /path/to/user.env
```

Before publication or in an isolated network, install a supplied tarball with `npm install --global /path/to/jagent-0.1.1.tgz`. It includes its runtime dependencies. To produce it from a checkout, run `npm ci` and `npm pack` in `sdk/node`. For the default local Harness use `jagent runner`. On a headless host add `--no-browser`. Use `npm uninstall --global jagent` to remove the CLI.

## Use the CLI

| Command | Purpose |
| --- | --- |
| `jagent version` | Print the installed Runner version. Works offline. |
| `jagent runner` | Start the Runner using its saved connection. |
| `jagent open --link` | Print a single-use Console sign-in link. |
| `jagent pair <code>` | Confirm another browser from the Runner's terminal. |
| `jagent recover --session <id>` | Return recorded results for an interrupted run after stopping the serving Runner. |

A Runner keeps its identity, job receipts and tool results under `~/.jagent-runner`, separated by server and user. The Harness owns the SQLite or PostgreSQL database. Runner machines need filesystem access to their workspaces and outbound HTTP(S) access to the Harness. Use HTTPS on untrusted networks.

Use `--home` for another identity, `--name` and `--description` for its Console listing, or `--console` for a separate Console URL. When several profiles match, `--profile <id>` selects the ID printed at registration. An idle Runner exits after 24 hours of continuous disconnection by default. Set `--idle-disconnect-timeout <seconds>` to change this. The credential, server and timeout environment variables match the [configuration reference](../../QUICK_START.md#configuration-reference).

## Embed your own tools

The package exports a typed SDK. Supply the tools available on the device. Add `workspaces` only if Projects choose a local directory.

```js
import { AgentRunner, success } from 'jagent'

const runner = await AgentRunner.create({
  server: process.env.JAGENT_SERVER_URL,
  token: process.env.JAGENT_USER_TOKEN,
  name: 'Device clock',
  tools: [{
    name: 'clock',
    description: 'Read the device time.',
    parameters: { type: 'object', properties: {}, additionalProperties: false },
    execute: () => success(new Date().toISOString())
  }]
})
process.once('SIGINT', () => runner.stop())
await runner.serve()
```

Run `npm test` here for local tool and recovery tests. To include an end-to-end test against a packaged Harness, set `JAGENT_TEST_SERVER_JAR` to its absolute JAR path. The test uses a temporary Harness database and a local model fixture.
