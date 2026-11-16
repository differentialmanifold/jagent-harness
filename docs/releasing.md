# Releasing Maven artifacts

[Documentation](README.md) · [Development](../CONTRIBUTING.md)

Framework artifacts use the `io.github.differentialmanifold` group. The root and SDK parent POMs, shared `jagent-api`, Java client, and server modules are published together. Applications under `examples/` are excluded from publication.

## Verify locally

From the repository root, run the checks in the [development guide](../CONTRIBUTING.md#build-and-check), then build release attachments without uploading or signing:

```sh
mvn -pl jagent-api,sdk/java,jagent-harness-sdk/jagent-console-spring-boot-starter,jagent-harness-sdk/jagent-store-jdbc -am -P release -Dgpg.skip verify
```

The release profile creates source and Javadoc JARs. Normal `package`, `verify`, and `install` builds do not upload artifacts.

## Publish

1. Set a non-SNAPSHOT version in the root POM. Update module parents, framework dependencies, the demo's `jagent.version`, Console/protocol package versions and lockfile roots, and OpenAPI metadata. Keep Maven snippets and executable JAR names in the READMEs and Quick start aligned; do not change third-party dependency versions as part of this update.
2. Verify the complete build and tests.
3. Configure repository secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, and `GPG_PASSPHRASE`.
4. Push a `v<version>` tag or manually run the **Publish Maven artifacts** workflow. A tag must match the root POM version.

The workflow runs the checks and then publishes with:

```sh
mvn -B -pl jagent-api,sdk/java,jagent-harness-sdk/jagent-console-spring-boot-starter,jagent-harness-sdk/jagent-store-jdbc -am -P release deploy
```

For local publication, configure the `central` server in Maven settings and provide `MAVEN_GPG_KEY` and `MAVEN_GPG_PASSPHRASE` in the environment. The Central plugin has `autoPublish=true`; a release `deploy` uploads and publishes the artifacts.

Keep the API version, JSON Schema, OpenAPI, and protocol fixtures consistent when changing the public contract.

Tagged releases attach the Coding Runner offline ZIP (JAR plus version/checksum manifest), the web frontend archive, and SHA256SUMS. Operators can install the offline package on their Harness once and distribute it inside an isolated network. Runner deployment does not depend on reaching GitHub. The executable includes its coding tools and is not a Central library. See [local distribution](../examples/coding-runner/README.md#distribute-inside-your-network).

Release assets also include `jagent-skill-<version>.zip`, containing the complete standalone `jagent/` skill and its dependency-free Node.js client.

## Offline application package

Run `node scripts/package.mjs` from a checkout. It builds the existing Maven examples and Console, includes a filtered source snapshot and version/revision metadata, and writes `target/jagent-<version>.zip`. It does not publish anything. Tagged releases attach this package alongside the existing artifacts.

After extraction, users only need Java 8+, Node.js 22.12+ and model configuration. `node scripts/dev.mjs` starts the server, Coding Runner and built Console at port 5175, and configures mainbot with the bundled source. Source acquisition needs no internet at runtime. Build from a clean checkout for releases; dirty development builds are marked explicitly. Maven consumers can continue using the libraries independently.

The Coding Runner embeds its Maven product version and `harness.source.revision` in `coding-runner-build.properties` and reports them at registration. The launcher, packaging script and release workflow already pass the source revision (including the dirty marker for local builds). For direct Maven builds, supply `-Dharness.source.revision=<commit-or-build-id>` when available; otherwise the build revision is reported as unknown. Runner versions are visible through `/api/v1/runners` and System Settings → Runners without inspecting the JAR.
