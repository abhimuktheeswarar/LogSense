plugins {
    alias(libs.plugins.android.library)
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
