plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-gradle-plugin")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.0.21")
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
    testImplementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.0.21")
    testImplementation("junit:junit:4.13.2")
}
