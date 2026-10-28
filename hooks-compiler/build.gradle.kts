plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")

    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")
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
