# Publishing LogSense to Maven Central

Publishes `com.msabhi:logsense` and `com.msabhi:logsense-no-op` via the
[vanniktech maven-publish plugin](https://github.com/vanniktech/gradle-maven-publish-plugin)
to the Central Portal.

## One-time machine setup

Publishing needs these keys in the gitignored `local.properties` (copy them
from another machine that has them — never commit them):

```properties
SONATYPE_USERNAME=<Central Portal token username>
SONATYPE_PASSWORD=<Central Portal token password>
SIGNING_KEY=<ASCII-armored GPG private key, \n-escaped onto one line>
SIGNING_PASSWORD=<GPG key passphrase>
```

## Release steps

1. **Bump the version** — `VERSION_NAME` in `gradle.properties`, and the
   version in the README dependency snippet.

2. **Verify locally**

   ```bash
   ./gradlew :logsense:testDebugUnitTest
   ./gradlew :logsense:publishToMavenLocal :logsense-no-op:publishToMavenLocal -PskipSigning=true
   ls ~/.m2/repository/com/msabhi/logsense/<version>/   # expect .aar, -sources.jar, -javadoc.jar, .pom, .module
   ```

3. **Publish + auto-release**

   ```bash
   ./gradlew publishAndReleaseToMavenCentralFromLocal
   ```

   This root wrapper task re-invokes Gradle with the Central credentials
   injected as `ORG_GRADLE_PROJECT_*` env vars. Do NOT run
   `publishAndReleaseToMavenCentral` directly — on Gradle 9 the plugin only
   sees credentials passed as real Gradle properties, so the direct task
   fails with `mavenCentralUsername not found`.

4. **Wait for sync** — check the deployment at
   <https://central.sonatype.com/publishing/deployments> (state goes
   VALIDATING → PUBLISHING → PUBLISHED). Artifacts resolve from
   `repo1.maven.org` typically within the hour; the search UI lags longer.

5. **Commit, tag, release**

   ```bash
   git commit -am "Release <version>"
   git tag -a v<version> -m "LogSense <version>"
   git push && git push origin v<version>
   gh release create v<version> --title "LogSense <version>" --notes "..."
   ```

Central rejects re-uploads of an existing version — a botched release needs a
new version number.
