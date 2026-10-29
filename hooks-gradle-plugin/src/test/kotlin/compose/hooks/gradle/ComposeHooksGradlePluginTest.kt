package compose.hooks.gradle

import java.lang.reflect.Proxy
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.internal.project.ProjectInternal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeHooksGradlePluginTest {
    private val plugin = ComposeHooksGradlePlugin()

    /** a KotlinCompilation whose only used member is `target.project` */
    private fun compilation(project: Project): KotlinCompilation<*> {
        val target = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(KotlinTarget::class.java)) { _, m, _ ->
            if (m.name == "getProject") project else throw UnsupportedOperationException(m.name)
        } as KotlinTarget
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(KotlinCompilation::class.java)) { _, m, _ ->
            if (m.name == "getTarget") target else throw UnsupportedOperationException(m.name)
        } as KotlinCompilation<*>
    }

    @Test fun pluginIdMatchesTheCompilerPlugin() {
        assertEquals("compose.hooks", plugin.getCompilerPluginId())
    }

    @Test fun artifactCoordinates() {
        val a = plugin.getPluginArtifact()
        assertEquals("compose.hooks", a.groupId)
        assertEquals("hooks-compiler", a.artifactId)
        assertEquals("1.0.1", a.version)
    }

    @Test fun applicableToEveryCompilationWithNoOptions() {
        val project = ProjectBuilder.builder().build()
        val c = compilation(project)
        assertTrue(plugin.isApplicable(c))
        assertEquals(emptyList<Any>(), plugin.applyToCompilation(c).get())
    }

    @Test fun appliesById() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("compose.hooks")
        assertTrue(project.plugins.hasPlugin(ComposeHooksGradlePlugin::class.java))
        assertTrue(project.plugins.getPlugin(ComposeHooksGradlePlugin::class.java) is KotlinCompilerPluginSupportPlugin)
    }

    // ---- Android unit tests: isReturnDefaultValues ----

    private fun libraryReturnDefaults(project: Project) =
        project.extensions.getByType(LibraryExtension::class.java).testOptions.unitTests.isReturnDefaultValues

    @Test fun androidLibraryGetsReturnDefaultValues_eitherOrder() {
        val after = ProjectBuilder.builder().build()
        after.pluginManager.apply("com.android.library")
        assertFalse("AGP default", libraryReturnDefaults(after))
        after.pluginManager.apply("compose.hooks")
        assertTrue(libraryReturnDefaults(after))

        val before = ProjectBuilder.builder().build()
        before.pluginManager.apply("compose.hooks")
        before.pluginManager.apply("com.android.library")
        assertTrue(libraryReturnDefaults(before))
    }

    @Test fun androidApplicationToo() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("compose.hooks")
        project.pluginManager.apply("com.android.application")
        assertTrue(project.extensions.getByType(ApplicationExtension::class.java).testOptions.unitTests.isReturnDefaultValues)
    }

    @Test fun anExplicitValueInTheBuildScriptWins() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.android.library")
        project.pluginManager.apply("compose.hooks")
        project.extensions.getByType(LibraryExtension::class.java).testOptions.unitTests.isReturnDefaultValues = false
        assertFalse(libraryReturnDefaults(project))
    }

    @Test fun noOrForeignAndroidExtensionIsANoOp() {
        val plain = ProjectBuilder.builder().build()
        assertFalse(ComposeHooksGradlePlugin.configureAndroidUnitTests(plain))
        val foreign = ProjectBuilder.builder().build()
        foreign.extensions.add("android", "not an android extension")
        assertFalse(ComposeHooksGradlePlugin.configureAndroidUnitTests(foreign))
    }

    // ---- Compose compiler check ----

    @Test fun warnsForKotlinWithoutTheComposeCompiler() {
        val project = ProjectBuilder.builder().build()
        assertNull("not a Kotlin module", ComposeHooksGradlePlugin.missingComposeCompiler(project))
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.pluginManager.apply("compose.hooks")
        assertTrue(ComposeHooksGradlePlugin.missingComposeCompiler(project)!!.contains("org.jetbrains.kotlin.plugin.compose"))
        (project as ProjectInternal).evaluate() // runs the afterEvaluate check (logs the warning)
        project.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        assertNull(ComposeHooksGradlePlugin.missingComposeCompiler(project))
    }

    // ---- Kotlin version check ----

    /** a Kotlin plugin reporting [version] (KGP itself is the build's version) */
    abstract class FakeKotlin(private val version: String) : KotlinBasePlugin {
        override val pluginVersion: String get() = version
        override fun apply(target: Project) {}
    }
    class Kotlin20 : FakeKotlin("2.0.21")
    class Kotlin23 : FakeKotlin("2.3.21")

    @Test fun parsesKotlinVersions() {
        assertEquals(listOf(2, 3, 21), ComposeHooksGradlePlugin.parseVersion("2.3.21"))
        assertEquals(listOf(2, 3, 20), ComposeHooksGradlePlugin.parseVersion("2.3.20-Beta1"))
        assertEquals(listOf(2, 4, 0), ComposeHooksGradlePlugin.parseVersion("2.4"))
        assertNull(ComposeHooksGradlePlugin.parseVersion("unknown"))
    }

    @Test fun supportedKotlinRange() {
        listOf("2.2.20", "2.2.21", "2.3.0", "2.3.21", "2.3.255", "2.3.30-RC").forEach { assertNull(it, ComposeHooksGradlePlugin.unsupportedKotlin(it)) }
        listOf("2.0.21", "2.2.10", "2.2.0", "2.4.0", "2.4.0-dev-1", "3.0.0", "unknown").forEach {
            val warning = ComposeHooksGradlePlugin.unsupportedKotlin(it)!!
            assertTrue(warning, warning.contains("Kotlin $it is not supported") && warning.contains(">= 2.2.20 and < 2.4.0"))
        }
    }

    @Test fun theBuildsKotlinIsSupported() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.pluginManager.apply("compose.hooks")
        assertTrue(ComposeHooksGradlePlugin.supportsKotlinOf(project))
        assertTrue(plugin.isApplicable(compilation(project)))
    }

    @Test fun anUnsupportedKotlinSkipsTheChecksWithAWarning() {
        val old = ProjectBuilder.builder().build()
        old.pluginManager.apply("compose.hooks")
        old.plugins.apply(Kotlin20::class.java) // logs the warning
        assertFalse(ComposeHooksGradlePlugin.supportsKotlinOf(old))
        assertFalse(plugin.isApplicable(compilation(old)))

        val current = ProjectBuilder.builder().build()
        current.plugins.apply(Kotlin23::class.java)
        current.pluginManager.apply("compose.hooks")
        assertTrue(plugin.isApplicable(compilation(current)))
    }
}
