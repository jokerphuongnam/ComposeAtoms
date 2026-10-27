plugins {
    id("org.jetbrains.kotlin.jvm")
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
