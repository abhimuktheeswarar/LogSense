# Publishing LogSense

Both platforms release **in lockstep** under one annotated tag `vX.Y.Z` on `master`:

- **iOS** is consumed by Swift Package Manager straight from the git tag (the root
  `Package.swift` points into `apple/`). SwiftPM resolves `v`-prefixed semver tags, so
  `from: "0.6.0"` matches tag `v0.6.0`. Never create both `0.6.0` and `v0.6.0` — SwiftPM
  errors on duplicate versions.
- **Android** publishes `com.msabhi:logsense` and `com.msabhi:logsense-no-op` to Maven Central
  (Central Portal) via the
  [vanniktech maven-publish plugin](https://github.com/vanniktech/gradle-maven-publish-plugin),
  at the same version as the tag — even when one platform's code is unchanged, both ship, so
  tag = Maven version = SPM version always.

## One-time machine setup (Android publishing)

Publishing needs these keys in the gitignored `android/local.properties` (copy them from
another machine that has them — never commit them):

```properties
SONATYPE_USERNAME=<Central Portal token username>
SONATYPE_PASSWORD=<Central Portal token password>
SIGNING_KEY=<ASCII-armored GPG private key, \n-escaped onto one line>
SIGNING_PASSWORD=<GPG key passphrase>
```

## Release steps

1. **Bump the version** — `VERSION_NAME` in `android/gradle.properties`, `logSenseVersion` in
   `apple/Sources/LogSense/Internal/UI/SettingsScreen.swift` (shown in the iOS Settings screen;
   SPM has no build-time tag injection), plus the version in the dependency snippets of
   `README.md`, `android/README.md` and `apple/README.md`.

2. **Verify Android**

   ```bash
   cd android
   ./gradlew :logsense:testDebugUnitTest
   ./gradlew :logsense:publishToMavenLocal :logsense-no-op:publishToMavenLocal -PskipSigning=true
   ls ~/.m2/repository/com/msabhi/logsense/<version>/   # expect .aar, -sources.jar, -javadoc.jar, .pom, .module
   cd ..
   ```

3. **Verify iOS** (from the repository root)

   ```bash
   swift test
   xcodebuild -scheme LogSense -destination 'generic/platform=iOS Simulator' build
   ```

4. **Land on master** — releases are tagged on `master` only:

   ```bash
   git checkout master && git merge --ff-only <feature-branch> && git push origin master
   ```

5. **Publish Android to Maven Central**

   ```bash
   cd android && ./gradlew publishAndReleaseToMavenCentralFromLocal && cd ..
   ```

   This root wrapper task re-invokes Gradle with the Central credentials injected as
   `ORG_GRADLE_PROJECT_*` env vars. Do NOT run `publishAndReleaseToMavenCentral` directly —
   on Gradle 9 the plugin only sees credentials passed as real Gradle properties, so the
   direct task fails with `mavenCentralUsername not found`.

   Wait for sync: check <https://central.sonatype.com/publishing/deployments> (VALIDATING →
   PUBLISHING → PUBLISHED). Artifacts resolve from `repo1.maven.org` typically within the
   hour; the search UI lags longer. Central rejects re-uploads of an existing version — a
   botched release needs a new version number.

6. **Tag + GitHub release** — the tag IS the iOS release:

   ```bash
   git commit -am "Release <version>"        # if the version bump wasn't committed yet
   git tag -a v<version> -m "LogSense <version>"
   git push && git push origin v<version>
   gh release create v<version> --title "LogSense <version>" --notes "..."
   ```

7. **Prove SPM resolution** — from an empty scratch directory:

   ```bash
   swift package init --type executable
   # add to Package.swift:
   #   .package(url: "https://github.com/abhimuktheeswarar/LogSense.git", from: "<version>")
   swift package resolve
   ```

   Resolution succeeding against the fresh tag is the release's exit criterion.
