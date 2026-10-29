package compose.hooks.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * `plugins { id("compose.hooks") }`:
 * - adds the ComposeHooks compiler plugin (FIR checks E1/E2/E3/B5) to every Kotlin compilation, unit tests included;
 * - in an Android module, sets `android.testOptions.unitTests.isReturnDefaultValues = true`: the Compose runtime
 *   calls `android.os.Trace`, which throws in JVM unit tests (hooks-test) without it. Set at plugin-apply time, so an
 *   explicit value in the module's `android { }` block still wins;
 * - warns when the Kotlin Compose compiler plugin is missing (hooks and their tests are @Composable code);
 * - checks the Kotlin Gradle plugin version: the compiler plugin uses the K2 FIR API, which changes between Kotlin
 *   releases, so it is added only for Kotlin [MIN_KOTLIN] until (not including) [MAX_KOTLIN_EXCLUSIVE]. On another
 *   version the module builds without the checks and a warning says so (the libraries work on any Kotlin version).
 *
 * Inside this repository :example wires `:hooks-compiler` directly through `kotlinCompilerPluginClasspath`, because
 * this plugin resolves the published artifact below.
 */
class ComposeHooksGradlePlugin : KotlinCompilerPluginSupportPlugin {
    /** Android unit-test defaults and the Compose-compiler check (see the class doc). */
    override fun apply(target: Project) {
        ANDROID_PLUGINS.forEach { id ->
            target.pluginManager.withPlugin(id) { configureAndroidUnitTests(target) }
        }
        target.afterEvaluate { missingComposeCompiler(target)?.let(target.logger::warn) }
        target.plugins.withType(KotlinBasePlugin::class.java).configureEach { kotlin ->
            unsupportedKotlin(kotlin.pluginVersion)?.let(target.logger::warn)
        }
    }

    /** Every compilation (main and test) of a module on a supported Kotlin version. */
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        supportsKotlinOf(kotlinCompilation.target.project)

    /** `compose.hooks`. */
    override fun getCompilerPluginId(): String = "compose.hooks"

    /** `compose.hooks:hooks-compiler:[VERSION]`. */
    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(groupId = "compose.hooks", artifactId = "hooks-compiler", version = VERSION)

    /** No options. */
    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider { emptyList() }

    /** Constants. */
    companion object {
        /** The hooks-compiler version this plugin adds (the release version). */
        const val VERSION = "1.0.1"
        /** The Kotlin Compose compiler Gradle plugin id. */
        const val COMPOSE_COMPILER = "org.jetbrains.kotlin.plugin.compose"
        /**
         * The first Kotlin version the compiler plugin runs on (KtDiagnosticsContainer and the context-parameter
         * FIR checker API). Fixture tests pass on 2.2.20, 2.2.21, 2.3.0, 2.3.10 and 2.3.21.
         */
        const val MIN_KOTLIN = "2.2.20"
        /** The first Kotlin version it is not known to run on. */
        const val MAX_KOTLIN_EXCLUSIVE = "2.4.0"
        internal val ANDROID_PLUGINS = listOf("com.android.library", "com.android.application")
        internal val KOTLIN_PLUGINS = listOf("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.jvm")

        /**
         * `android.testOptions.unitTests.isReturnDefaultValues = true`, by reflection: AGP may live in another
         * class loader than this plugin, and this plugin must not need AGP on its own classpath.
         * Returns false when the module has no `android` extension of the expected shape.
         */
        internal fun configureAndroidUnitTests(project: Project): Boolean {
            val android = project.extensions.findByName("android") ?: return false
            return try {
                val testOptions = android.javaClass.getMethod("getTestOptions").invoke(android)
                val unitTests = testOptions.javaClass.getMethod("getUnitTests").invoke(testOptions)
                unitTests.javaClass.getMethod("setReturnDefaultValues", Boolean::class.javaPrimitiveType).invoke(unitTests, true)
                true
            } catch (e: ReflectiveOperationException) {
                project.logger.warn("compose.hooks: could not set android.testOptions.unitTests.isReturnDefaultValues ($e)")
                false
            }
        }

        /** The warning for a Kotlin module without the Compose compiler plugin, or null. */
        internal fun missingComposeCompiler(project: Project): String? {
            val kotlin = KOTLIN_PLUGINS.any { project.pluginManager.hasPlugin(it) }
            if (!kotlin || project.pluginManager.hasPlugin(COMPOSE_COMPILER)) return null
            return "compose.hooks: ${project.path} does not apply '$COMPOSE_COMPILER'; hooks (and hooks-test tests) " +
                "are @Composable and need the Compose compiler."
        }

        /** major, minor, patch of "2.3.21" / "2.3.20-Beta1" / "2.4.0-dev-1", or null. */
        internal fun parseVersion(version: String): List<Int>? =
            Regex("""^(\d+)\.(\d+)(?:\.(\d+))?""").find(version)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: 0 }

        private fun compare(a: List<Int>, b: List<Int>): Int =
            a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0

        /** The warning for a Kotlin version the compiler plugin does not support, or null when it is supported. */
        internal fun unsupportedKotlin(version: String): String? {
            val v = parseVersion(version)
            if (v != null && compare(v, parseVersion(MIN_KOTLIN)!!) >= 0 && compare(v, parseVersion(MAX_KOTLIN_EXCLUSIVE)!!) < 0) {
                return null
            }
            return "compose.hooks: Kotlin $version is not supported by the ComposeHooks compiler checks " +
                "(E1/E2/E3/B5 need Kotlin >= $MIN_KOTLIN and < $MAX_KOTLIN_EXCLUSIVE); building without them. " +
                "The hooks / query / test libraries are not affected."
        }

        /** True unless the project's Kotlin Gradle plugin has an unsupported version. */
        internal fun supportsKotlinOf(project: Project): Boolean =
            project.plugins.withType(KotlinBasePlugin::class.java).none { unsupportedKotlin(it.pluginVersion) != null }
    }
}
