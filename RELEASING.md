# Releasing

JAgentHarness publishes only the SDK reactor under `jagent-harness-sdk/`.
The root development reactor, examples, frontend, and design notes are not published to Maven Central.

## Prerequisites

Create these GitHub repository secrets before publishing:

| Secret | Description |
| --- | --- |
| `CENTRAL_USERNAME` | Sonatype Central Portal user token username. |
| `CENTRAL_PASSWORD` | Sonatype Central Portal user token password. |
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private key used to sign artifacts. |
| `GPG_PASSPHRASE` | Passphrase for the GPG private key. |

Export the signing key with:

```zsh
gpg --armor --export-secret-keys <KEY_ID>
```

Release signing uses the Maven GPG Plugin `bc` signer. The armored private key is
passed to Maven through `MAVEN_GPG_KEY`; the workflow does not import the key into
`~/.gnupg`.

The Sonatype namespace must allow publishing under:

```text
io.github.differentialmanifold
```

## Release Flow

1. Create a release branch from `main`.

   ```zsh
   git checkout main
   git pull
   git checkout -b release/0.2.0
   ```

2. Set Maven versions to the release version.

   ```zsh
   mvn -f pom.xml versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
   ```

3. Run local checks.

   ```zsh
   mvn -f pom.xml test
   mvn -B -f jagent-harness-sdk/pom.xml -P release -Dgpg.skip verify
   ```

4. Open a pull request into `main` and wait for CI to pass.

   The `BC Signing` workflow performs a real release-profile signing check with
   the `bc` signer, but it does not deploy artifacts.

5. Squash merge the pull request.

6. Tag the release from `main`.

   ```zsh
   git checkout main
   git pull
   git tag -a v0.2.0 -m "Release v0.2.0"
   git push origin v0.2.0
   ```

7. The `Publish SDK` GitHub Actions workflow publishes the SDK modules to Maven Central.

8. After the release is published, open a follow-up pull request to bump development versions.

   ```zsh
   mvn -f pom.xml versions:set -DnewVersion=0.2.1-SNAPSHOT -DgenerateBackupPoms=false
   ```
