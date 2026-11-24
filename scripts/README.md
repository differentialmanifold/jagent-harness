# Run JAgentHarness

[Quick start and model settings](../README.md)

## Start locally

Copy `.env.example` to `.env.local` and set the model connection. Start the Harness, Console and Runner together from the application root:

| Platform | Start locally |
| --- | --- |
| macOS | Double-click `scripts/macos/start.command`. |
| Windows | Double-click `scripts/windows/start.cmd`. |
| Linux or any terminal | Run `node scripts/start.mjs local`. |

To start a single service, use `node scripts/start.mjs harness` or `node scripts/start.mjs frontend`. Run these commands from the application root. Press Ctrl+C to stop.

## Install the global Runner from source

From the repository root, run:

```sh
cd sdk/node
npm ci
npm pack
npm install --global ./jagent-0.1.1.tgz
jagent version
```

Use the filename printed by `npm pack`. On the execution machine, connect with `jagent runner --server <url> --credentials <file>`. To uninstall, run `npm uninstall --global jagent`.

To build the application ZIP and Runner tarball from a checkout, run `node scripts/package.mjs` from the repository root. Both files are written to `target/`.
