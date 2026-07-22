import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
    id("signing")
}

android {
    // Kotlin package stays com.msabhi.logsense (API parity with :logsense);
    // namespace differs only so the R classes don't clash.
    namespace = "com.msabhi.logsense.noop"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.contains("Test")) compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
}

// Signing keys come from the gitignored local.properties; Maven Central credentials
// flow in via the root publishAndReleaseToMavenCentralFromLocal wrapper task.
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.reader().use { reader -> localProps.load(reader) }
}

mavenPublishing {
    publishToMavenCentral()
    pom {
        issueManagement {
            system.set("Github issues")
            url.set("https://github.com/abhimuktheeswarar/LogSense/issues")
        }
    }
}

signing {
    val signingKey = localProps.getProperty("SIGNING_KEY")
    val signingPassword = localProps.getProperty("SIGNING_PASSWORD")
    val skipSigning = project.findProperty("skipSigning") == "true"
    if (!skipSigning && !signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
