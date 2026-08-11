// Top-level build file where you can add configuration options common to all sub-projects/modules.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
}

// The vanniktech plugin only reads Maven Central credentials from real Gradle
// properties, so this wrapper re-invokes Gradle with them injected as
// ORG_GRADLE_PROJECT_* env vars, loaded from the gitignored local.properties.
tasks.register<Exec>("publishAndReleaseToMavenCentralFromLocal") {
    group = "publishing"
    description = "Publishes to Maven Central with automatic release, loading credentials from local.properties"
    val localProps = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { localProps.load(it) }
    val gradlewName = if (System.getProperty("os.name").lowercase().contains("windows")) "gradlew.bat" else "gradlew"
    commandLine(File(projectDir, gradlewName).absolutePath, "publishAndReleaseToMavenCentral")
    workingDir = projectDir
    environment("JAVA_HOME", System.getProperty("java.home"))
    localProps.getProperty("SONATYPE_USERNAME")?.takeIf(String::isNotBlank)?.let {
        environment("ORG_GRADLE_PROJECT_mavenCentralUsername", it)
    }
    localProps.getProperty("SONATYPE_PASSWORD")?.takeIf(String::isNotBlank)?.let {
        environment("ORG_GRADLE_PROJECT_mavenCentralPassword", it)
    }
}