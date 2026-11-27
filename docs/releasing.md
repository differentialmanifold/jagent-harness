# Releasing

[Documentation](README.md) · [Development checks](../CONTRIBUTING.md#build-and-check)

## Runner on npm

`sdk/node` is the `jagent` npm package. Its independent version starts at `0.1.0`. It exports the SDK and installs the `jagent` CLI. Run `jagent version` to check the installed version. The package includes TypeScript sources, compiled JavaScript, declarations, runtime dependencies and the license. Client machines need Node.js 20 (20.19 or later) or Node.js 22.12+.

1. Set the package version and update `package-lock.json` in `sdk/node`. Run `npm ci` and `npm test` there. Set `JAGENT_TEST_SERVER_JAR` to the absolute path of the packaged Harness to include the protocol integration test.
2. Run `npm publish --dry-run` to inspect the files. Run `npm pack` and test the resulting archive with `npm install -g /path/to/jagent-<version>.tgz`. A private npm registry or an operator-supplied tarball can serve an internal deployment.
3. **First public release.** Confirm the unscoped name is available, sign in to the intended npm account with publishing permissions and 2FA, then run `npm publish ./jagent-<version>.tgz --access public` from `sdk/node`. If the name is unavailable, choose a scope you own and update package references before releasing. The executable can still be called `jagent`.
4. **Subsequent releases.** In the npm package settings, configure GitHub Actions as a trusted publisher for `differentialmanifold/jagent-harness` and workflow `publish-runner.yml`. Leave **Allow npm publish** unchecked: the workflow only stages. Push a `runner-v<package-version>` tag for a new version. The workflow checks the tag, tests against the real Harness, installs the packed CLI, and stages that tarball with the `latest` tag via OIDC. The version stays private until a maintainer reviews it under **Staged Packages** on npmjs.com and clicks **Approve**, or runs `npm stage approve <stage-id>` with npm 11.15 or later. Both confirm with 2FA. [npm trusted publishing](https://docs.npmjs.com/trusted-publishers/) · [npm staged publishing](https://docs.npmjs.com/staged-publishing/)

## Harness and Console releases

Set the Maven and Console versions and push a matching `v<version>` tag. CI builds the standalone Harness with Maven, tests the Runner against that JAR and builds the Console. The GitHub release contains:

- `jagent-server-<version>.jar` with its source snapshot.
- `jagent-console-<version>.zip` with the static site, build identity and a Node.js launcher.
- The installable Runner tarball at its independent npm version, the external Agent Skill ZIP and checksums.

The Maven modules build together in the reactor. The release workflow uploads the executable directly to GitHub. npm publication follows the separate Runner tag above.
