package compose.hooks.gradle

import java.lang.reflect.Proxy
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.junit.Assert.assertEquals
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
        assertEquals("0.1.0", a.version)
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
}
