# Releasing Maven artifacts

Framework artifacts use the `io.github.differentialmanifold` group. The root and SDK parent POMs, shared `jagent-api`, Java client, and server modules are published together. Applications under `examples/` are excluded from publication.

## Verify locally

Run the checks in the [development guide](README.md#development), then build release attachments without uploading or signing:

```sh
mvn -pl jagent-api,sdk/java,jagent-harness-sdk/jagent-console-spring-boot-starter,jagent-harness-sdk/jagent-store-jdbc -am -P release -Dgpg.skip verify
```

The release profile creates source and Javadoc JARs. Normal `package`, `verify`, and `install` builds do not upload artifacts.

## Publish

1. Set a non-SNAPSHOT version in the root POM. Update module parent versions, framework dependency versions, and the `jagent.version` property in `examples/server-demo/pom.xml` consistently.
2. Verify the complete build and tests.
3. Configure repository secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, and `GPG_PASSPHRASE`.
4. Push a `v<version>` tag or manually run the **Publish Maven artifacts** workflow. A tag must match the root POM version.

The workflow runs the checks and then publishes with:

```sh
mvn -B -pl jagent-api,sdk/java,jagent-harness-sdk/jagent-console-spring-boot-starter,jagent-harness-sdk/jagent-store-jdbc -am -P release deploy
```

For local publication, configure the `central` server in Maven settings and provide `MAVEN_GPG_KEY` and `MAVEN_GPG_PASSPHRASE` in the environment. The Central plugin has `autoPublish=true`; a release `deploy` uploads and publishes the artifacts.

Keep the API version, JSON Schema, OpenAPI, and protocol fixtures consistent when changing the public contract.
