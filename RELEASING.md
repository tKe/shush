# Releasing

Pushing a `v*` tag runs [`release.yml`](.github/workflows/release.yml), which takes the version from the tag and:

1. runs `check`;
2. publishes and releases `io.github.tke:shush-compiler-plugin` and `io.github.tke:shush-gradle-plugin` (with the
   plugin marker) to Maven Central;
3. publishes plugin id `io.github.tke.shush` to the Gradle Plugin Portal;
4. creates a GitHub release with generated notes.

```sh
git tag -s v0.1.0 -m "shush 0.1.0"
git push origin v0.1.0
```

The version comes only from the tag: `gradle.properties` stays at `0-SNAPSHOT` for local builds and is never bumped.

## Setup

The workflow runs in the `publish` environment (Settings → Environments); add required reviewers there to approve each
release. It needs these secrets:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | Central Portal user token (central.sonatype.com → View Account → Generate User Token), for the verified `io.github.tke` namespace |
| `SIGNING_KEY` | `gpg --export-secret-keys --armor <key id>`; the public key must be on `keys.openpgp.org` or `keyserver.ubuntu.com` |
| `SIGNING_KEY_PASSWORD` | the signing key's passphrase |
| `GRADLE_PUBLISH_KEY`, `GRADLE_PUBLISH_SECRET` | Plugin Portal API key (plugins.gradle.org → API Keys) |

The first Plugin Portal release of the plugin is held for manual approval.

## When a release fails

Each publishing step checks first whether its result already exists (on Maven Central, on the Plugin Portal, as a
GitHub release) and is skipped if so, so a failed run can be re-run from the Actions tab.

- Before or during the Maven Central step, nothing is released: if Central rejects the deployment, it's left
  unpublished (drop it at central.sonatype.com → Deployments), and the fix can be tagged as the same version after
  moving the tag.
- Once Central has released a version it can't be replaced or removed. The check asks the Central Portal API for
  deployments that are publishing or published, so a re-run skips the Central step straight away rather than once
  `repo1.maven.org` serves the release.
