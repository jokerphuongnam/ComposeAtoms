plugins {
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // test-only coverage tooling (instruments unit-test runs; adds nothing to published artifacts)
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
    // KDoc -> javadoc jar for the two JVM modules (Android modules get theirs from AGP's bundled Dokka)
    id("org.jetbrains.dokka") version "1.9.20" apply false
}

// Every library module publishes compose.hooks:<module>:1.0.1 (with a sources jar) to mavenLocal (publishToMavenLocal)
// or GitHub Packages (publish). Credentials come from gradle properties gpr.user / gpr.key or the env
// GITHUB_ACTOR / GITHUB_TOKEN; never put a token in this repository.
/** The `description` of each published module's POM (a published module missing here fails the build). */
val POM_DESCRIPTIONS = mapOf(
    "hooks" to "React-style hooks for Jetpack Compose whose state lives as long as the ViewModelStoreOwner.",
    "query" to "QueryClient, cached queries (rememberCache), seedCache, global and shared state for ComposeHooks.",
    "hooks-compiler" to "Kotlin K2 compiler plugin that checks the rules of hooks (E1, E2, E3, B5).",
    "hooks-gradle-plugin" to "Gradle plugin compose.hooks: adds the ComposeHooks compiler checks and unit-test defaults.",
    "hooks-test" to "JVM unit-test kit for ComposeHooks: runHookTest, renderHook, back-stack entries, virtual time.",
    "query-test" to "JVM unit-test kit for ComposeHooks query: runQueryTest with an isolated QueryClient per test.",
)

fun Project.pomProperty(name: String): String = requireNotNull(findProperty(name) as String?) { "gradle.properties: $name" }

subprojects {
    group = "compose.hooks"
    version = "1.0.1"
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/jokerphuongnam/ComposeHooks")
                    credentials {
                        username = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                        password = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
            // POM metadata: values in gradle.properties (pom.*), descriptions in POM_DESCRIPTIONS
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("ComposeHooks ${project.name}")
                    description.set(POM_DESCRIPTIONS.getValue(project.name))
                    url.set(pomProperty("pom.url"))
                    inceptionYear.set("2026")
                    licenses {
                        license {
                            name.set(pomProperty("pom.license.name"))
                            url.set(pomProperty("pom.license.url"))
                            distribution.set("repo")
                            comments.set("SPDX-License-Identifier: " + pomProperty("pom.license.spdx"))
                        }
                    }
                    developers {
                        developer {
                            id.set(pomProperty("pom.developer.id"))
                            name.set(pomProperty("pom.developer.name"))
                            url.set(pomProperty("pom.developer.url"))
                        }
                    }
                    scm {
                        url.set(pomProperty("pom.url"))
                        connection.set(pomProperty("pom.scm.connection"))
                        developerConnection.set(pomProperty("pom.scm.developerConnection"))
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set(pomProperty("pom.url") + "/issues")
                    }
                }
            }
        }
    }
    // every public declaration needs KDoc: `./gradlew dokkaHtml` warns "Undocumented: ..." otherwise
    plugins.withId("org.jetbrains.dokka") {
        tasks.withType<org.jetbrains.dokka.gradle.DokkaTask>().configureEach {
            suppressInheritedMembers.set(true) // e.g. QueryTestScope's members delegated to HookTestScope
            dokkaSourceSets.configureEach {
                reportUndocumented.set(true)
                skipEmptyPackages.set(true)
            }
        }
    }
    afterEvaluate {
        if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
            tasks.register("prepareKotlinBuildScriptModel")
        }
    }
}
