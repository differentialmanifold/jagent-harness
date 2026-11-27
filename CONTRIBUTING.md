# Development and documentation

[Project overview](README.md) · [Documentation](docs/README.md)

## Build and check

Use the tools and model configuration described in [Quick start](QUICK_START.md). Automated tests do not require a live model endpoint.

Run from the repository root:

```sh
mvn clean install
npm ci --prefix sdk/node
npm test --prefix sdk/node
node --test scripts/lib/*.test.mjs skills/tests/*.test.mjs
npm ci --prefix protocol
npm test --prefix protocol
npm ci --prefix console
npm test --prefix console
npm run build --prefix console
```

Run the checks relevant to a change during development; CI runs the full build on pull requests. Publication is a separate action described in [Releasing](docs/releasing.md).

## Source map

| Directory | Responsibility |
| --- | --- |
| `jagent-api/` | Shared tool contracts and wire types |
| `jagent-harness-sdk/` | Server core, persistence, Spring configuration and Web/channel adapters |
| `sdk/node/` | npm CLI, Runner SDK and native coding tools |
| `server/` | Runnable server host |
| `console/` | Web frontend |
| `protocol/` | HTTP/JSON contract, schemas and conformance tests |
| `skills/jagent/` | Distributable external Agent Skill and client |
| `scripts/` | Cross-platform startup and release packaging |

## Where documentation belongs

Keep one authoritative explanation for each subject; link to it from other entry points.

| Document | Owns | Does not need |
| --- | --- | --- |
| Root READMEs | Purpose, architecture, main capabilities, shortest demo and navigation | Exhaustive settings or implementation history |
| `QUICK_START.md` | First run, launchers/manual startup, demo environment settings and startup troubleshooting | Detailed feature tutorials |
| `docs/README.md` and its translation | Task-oriented directory of guides and references | Copies of those guides |
| `docs/guides/<feature>.md` | How a user achieves an outcome, where to click, what to observe | Code-level change logs or migrations |
| Component `README.md` | Deployment, extension APIs and component-specific settings | General Console feature instructions |
| `protocol/` | Application/Runner wire contracts and fixtures | Personal Agent tutorials |
| `skills/jagent/` | Installed external Agent workflow and HTTP/SDK reference | Links that require the rest of this repository |
| `docs/releasing.md` | Publication procedure and release artifacts | Day-to-day development instructions |

The standalone application is in `server/`. Put guided examples in `docs/guides/`.

## Document a feature with its implementation

1. Update the existing guide for that capability. Add a new guide only when it serves a distinct user task, and link it from the documentation index.
2. Start with the outcome and prerequisites, show the shortest useful procedure, then explain the expected result and relevant limitations. Match the actual UI labels, routes and defaults.
3. Update the owning reference when configuration or APIs change. Link there instead of copying the same table into a tutorial. Change README or Quick start only if the introduction or first-run experience changes.
4. Keep existing English/Chinese counterparts aligned. Use `name.md` and `name.zh-CN.md` with reciprocal language links; a new English-only guide can be linked from both indexes and identified as English.
5. Check relative links and heading anchors after moving files. Keep paths relative for GitHub browsing. Try changed commands where practical; documentation-only moves do not need model calls or a full application build.

Guides use English by default and start with a link back to the documentation index. Keep headings stable, use short sections, and describe current behavior rather than the sequence of past refactors. Remove superseded explanations instead of retaining multiple competing versions.
