plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
    id("org.jetbrains.dokka") // `dokkaHtml`: KDoc check (reportUndocumented); the AAR javadoc jar comes from AGP
    id("org.jetbrains.kotlinx.kover")
}

android {
    namespace = "compose.hooks.query.test"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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

// Coverage of the JVM unit tests (src/test) only.
kover {
    reports {
        variant("debug") {
            verify {
                rule("line coverage >= 95%") { minBound(95, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE) }
                rule("branch coverage >= 80%") { minBound(80, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH) }
            }
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "query-test"
                from(components["release"])
            }
        }
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    // TestScope / TestDispatcher APIs of kotlinx-coroutines-test are still marked experimental
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

dependencies {
    api(project(":hooks-test"))
    api(project(":query"))

    testImplementation("junit:junit:4.13.2")
}
