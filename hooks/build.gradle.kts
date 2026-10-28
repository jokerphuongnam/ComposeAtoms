plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
    id("org.jetbrains.dokka") // `dokkaHtml`: KDoc check (reportUndocumented); the AAR javadoc jar comes from AGP
    id("org.jetbrains.kotlinx.kover")
}

android {
    namespace = "compose.hooks"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// Coverage of the JVM unit tests (src/test) only. `./gradlew :hooks:koverVerifyDebug` fails below the bounds.
kover {
    reports {
        variant("debug") {
            verify {
                rule("line coverage >= 95%") { minBound(95, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE) }
                // regression guard: remaining partial branches are null-safety / defensive paths (see test report)
                rule("branch coverage >= 80%") { minBound(80, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH) }
            }
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "hooks"
                from(components["release"])
            }
        }
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    api("androidx.compose.runtime:runtime:1.7.6")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(project(":hooks-test")) // dogfooding: hooks' own tests use runHookTest / renderHook
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.compose.ui:ui:1.7.6") // LocalView for the no-ViewModelStoreOwner (inspection) path

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0") // <3.7 breaks on API 35+ (InputManager.getInstance)
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    androidTestImplementation("androidx.compose.foundation:foundation:1.7.6")
    androidTestImplementation("androidx.activity:activity-compose:1.9.3")
    androidTestImplementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
}
