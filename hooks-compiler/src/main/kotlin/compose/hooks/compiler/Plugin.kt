package compose.hooks.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/** The compiler plugin id (`-P plugin:compose.hooks:...`), same as the Gradle plugin id. */
const val PLUGIN_ID = "compose.hooks"

/** No options: the plugin is configured by being on the classpath. */
class HooksCommandLineProcessor : CommandLineProcessor {
    /** [PLUGIN_ID]. */
    override val pluginId: String = PLUGIN_ID
    /** None. */
    override val pluginOptions: Collection<AbstractCliOption> = emptyList()
}

/** Registers [HooksFirExtensionRegistrar] (K2 only). */
class HooksCompilerPluginRegistrar : CompilerPluginRegistrar() {
    /** [PLUGIN_ID]. */
    override val pluginId: String = PLUGIN_ID
    /** The checks are FIR checkers. */
    override val supportsK2: Boolean = true
    /** Adds the FIR extension. */
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(HooksFirExtensionRegistrar())
    }
}

/** Adds [HooksCheckers] and the diagnostics' messages. */
class HooksFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::HooksCheckers
    }
}
