package compose.hooks.compiler

import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HooksCommandLineProcessor, HooksCompilerPluginRegistrar, HooksFirExtensionRegistrar and the service files. */
class PluginTest {
    @Test fun commandLineProcessorHasIdAndNoOptions() {
        val p = HooksCommandLineProcessor()
        assertEquals("compose.hooks", p.pluginId)
        assertEquals(PLUGIN_ID, p.pluginId)
        assertTrue(p.pluginOptions.isEmpty())
    }

    @Test fun registrarSupportsK2() {
        assertTrue(HooksCompilerPluginRegistrar().supportsK2)
    }

    @Test fun serviceFilesPointAtThePluginClasses() {
        fun service(type: Class<*>) = javaClass.classLoader.getResources("META-INF/services/${type.name}").toList()
            .flatMap { it.readText().lines() }.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(service(CommandLineProcessor::class.java).contains(HooksCommandLineProcessor::class.java.name))
        assertTrue(service(CompilerPluginRegistrar::class.java).contains(HooksCompilerPluginRegistrar::class.java.name))
    }

    @Test fun pluginIsLoadedByTheCompiler() {
        // end to end: the jar's registrar wires HooksFirExtensionRegistrar -> HooksCheckers into a K2 compilation
        val (_, msgs) = Fixtures.compile("E2.kt")
        assertTrue(msgs.any { it.text.startsWith("[E2]") })
    }
}
