plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "compose.hooks.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "compose.hooks.example"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true // android.os.Trace in JVM unit tests (the compose.hooks Gradle plugin sets this)
    }
}

// repository-only: no maven-publish here; :example is never published or bundled into a library artifact

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":hooks"))
    implementation(project(":query"))
    implementation("androidx.navigation:navigation-compose:2.8.5")
    // ComposeHooks compiler plugin (FIR checks E1/E2/E3/B5) from the sibling module
    kotlinCompilerPluginClasspath(project(":hooks-compiler"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.foundation:foundation:1.7.6")

    // JVM unit tests of the example's hooks, with the published test kits
    testImplementation(project(":query-test"))
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0") // <3.7 breaks on API 35+ (InputManager.getInstance)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
}
