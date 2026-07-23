import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.vanniktech.maven.publish)
    id("signing")
}

android {
    namespace = "com.msabhi.logsense"
    resourcePrefix = "logsense_"
    compileSdk {
        version = release(35)
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.contains("Test")) compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20260719") // real org.json for unit tests (SDK stub throws)
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
