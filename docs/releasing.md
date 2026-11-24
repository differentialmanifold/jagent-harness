# Releasing

[Documentation](README.md) · [Development checks](../CONTRIBUTING.md#build-and-check)

## Runner on npm

`sdk/node` is the `jagent` npm package. Its independent version starts at `0.1.0`. It exports the SDK and installs the `jagent` CLI. Run `jagent version` to check the installed version. The package includes compiled JavaScript, declarations, runtime dependencies and the license. Client machines need Node.js 20 (20.19 or later) or Node.js 22.12+.

1. Set the package version and update `package-lock.json` in `sdk/node`. Run `npm ci` and `npm test` there. Set `JAGENT_TEST_SERVER_JAR` to the absolute path of the packaged Harness to include the protocol integration test.
2. Run `npm publish --dry-run` to inspect the files. Run `npm pack` and test the resulting archive with `npm install -g /path/to/jagent-<version>.tgz`. A private npm registry or an operator-supplied tarball can serve an internal deployment.
3. **First public release.** Confirm the unscoped name is available, sign in to the intended npm account with publishing permissions and 2FA, then run `npm publish ./jagent-<version>.tgz --access public` from `sdk/node`. If the name is unavailable, choose a scope you own and update package references before releasing. The executable can still be called `jagent`.
4. **Subsequent releases.** In the npm package settings, configure GitHub Actions as a trusted publisher for `differentialmanifold/jagent-harness` and workflow `publish-runner.yml`. Push a `runner-v<package-version>` tag for a new version. The workflow checks the tag, tests against the real Harness, installs the packed CLI, and publishes that tarball with the `latest` tag via OIDC. [npm trusted publishing](https://docs.npmjs.com/trusted-publishers/)

## Harness and application releases

The Maven reactor publishes the shared API and server libraries under `io.github.differentialmanifold`. Applications under `examples/` are excluded from Central. Set the framework versions, the server-demo dependency, Console/protocol metadata and documentation for the release. Run the development checks first.

Configure `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` as repository secrets. Push a matching `v<version>` tag to publish via the Maven workflow. To verify source and Javadoc attachments locally, use:

```sh
mvn -pl jagent-api,jagent-harness-sdk/jagent-console-spring-boot-starter,jagent-harness-sdk/jagent-store-jdbc -am -P release -Dgpg.skip verify
```

`node scripts/package.mjs` builds an application ZIP with the server JAR, Console, installed Node Runner and corresponding source snapshot, plus an installable Runner tarball in `target/`. The release attaches these, the standalone Harness JAR, Console and external Agent Skill ZIPs, and SHA256 checksums. The ZIP follows the Harness version and records the bundled npm version in `distribution.json` as `runnerVersion`. Maven and npm have separate release tags so each can ship independently.
