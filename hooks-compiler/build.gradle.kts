plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
    id("maven-publish")
    id("org.jetbrains.dokka")
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        freeCompilerArgs.add("-Xcontext-parameters") // FIR checker API (Kotlin 2.2+)
    }
}

// the Kotlin version of the build (set once, in the root plugins block)
val kotlinVersion: String = org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")

    // the fixture tests run the plugin inside this compiler; `-PfixtureKotlin=2.2.20` checks another supported
    // version (supported: 2.2.20 <= Kotlin < 2.4.0, see ComposeHooksGradlePlugin.MIN_KOTLIN)
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${findProperty("fixtureKotlin") ?: kotlinVersion}")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // fixture tests compile Kotlin sources in-process with this plugin on the plugin classpath
    dependsOn(tasks.jar)
    systemProperty("hooks.plugin.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    maxHeapSize = "1g"
    if (project.hasProperty("hooksDebug")) systemProperty("hooks.debug", "1")
}

// Coverage of the unit tests (src/test) only. `./gradlew :hooks-compiler:koverVerify` fails below the bounds.
kover {
    reports {
        verify {
            rule("line coverage >= 95%") { minBound(95, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE) }
            // regression guard: remaining partial branches are null-safety / defensive paths (see test report)
            rule("branch coverage >= 80%") { minBound(80, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH) }
        }
    }
}

// compose.hooks:hooks-compiler:1.0.0, the artifact the gradle plugin (SubpluginArtifact) adds to kotlinc
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "hooks-compiler"
            from(components["java"])
        }
    }
}

// the javadoc jar carries the Dokka-rendered KDoc (java's javadoc task sees no Java sources)
tasks.named<Jar>("javadocJar") {
    from(tasks.named("dokkaJavadoc"))
}
