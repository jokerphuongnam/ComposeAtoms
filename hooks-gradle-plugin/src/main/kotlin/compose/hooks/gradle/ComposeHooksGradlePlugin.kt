package compose.hooks.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * `plugins { id("compose.hooks") }` adds the ComposeHooks compiler plugin (FIR checks E1/E2/B5) to every Kotlin
 * compilation. Inside this repository :example wires `:hooks-compiler` directly through
 * `kotlinCompilerPluginClasspath`, because this plugin resolves the published artifact below.
 */
class ComposeHooksGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {}

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = "compose.hooks"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(groupId = "compose.hooks", artifactId = "hooks-compiler", version = "0.1.0")

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider { emptyList() }
}
