plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-gradle-plugin")
    id("maven-publish")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

// the Kotlin version of the build (set once, in the root plugins block)
val kotlinVersion: String = org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$kotlinVersion")
}

gradlePlugin {
    plugins {
        create("composeHooks") {
            id = "compose.hooks"
            implementationClass = "compose.hooks.gradle.ComposeHooksGradlePlugin"
        }
    }
}

// test-only: ProjectBuilder (gradleApi) + the Kotlin plugin API at test runtime; nothing here is published
dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$kotlinVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.android.tools.build:gradle:8.7.3") // apply com.android.library in ProjectBuilder tests
    testImplementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    testImplementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
}

// published as compose.hooks:hooks-gradle-plugin:1.0.1 (pluginMaven) + the plugin marker
// compose.hooks:compose.hooks.gradle.plugin:1.0.1 (java-gradle-plugin creates both)

kover {
    reports {
        verify {
            rule("line coverage >= 95%") { minBound(95, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE) }
            rule("branch coverage >= 80%") { minBound(80, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH) }
        }
    }
}

// the javadoc jar carries the Dokka-rendered KDoc (java's javadoc task sees no Java sources)
tasks.named<Jar>("javadocJar") {
    from(tasks.named("dokkaJavadoc"))
}
