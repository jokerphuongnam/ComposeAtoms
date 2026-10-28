package compose.hooks.compiler

import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services

/** Test-only harness: compiles fixture sources in-process (K2) with the plugin jar and collects diagnostics. */
internal object Fixtures {
    data class Msg(val severity: CompilerMessageSeverity, val file: String, val line: Int, val text: String)

    private val STUBS = listOf("Stubs.kt", "StubsRuntime.kt", "StubsCoroutines.kt", "StubsQuery.kt")

    val dir: File get() = File(javaClass.classLoader.getResource("fixtures/Stubs.kt")!!.toURI()).parentFile

    fun compile(vararg fixtures: String): Pair<ExitCode, List<Msg>> {
        val sources = (STUBS + fixtures).map { File(dir, it).path }
        val out = Files.createTempDirectory("hooks-fixture").toFile()
        val stdlib = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI()).path
        val msgs = ArrayList<Msg>()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = msgs.any { it.severity.isErr() }
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isErr() || severity == CompilerMessageSeverity.WARNING || severity == CompilerMessageSeverity.STRONG_WARNING) {
                    msgs += Msg(severity, location?.path?.substringAfterLast('/') ?: "", location?.line ?: 0, message)
                }
            }
        }
        val args = arrayOf(
            "-Xplugin=" + System.getProperty("hooks.plugin.jar"),
            "-classpath", stdlib, "-no-stdlib", "-no-reflect", "-language-version", "2.0",
            "-d", out.path,
        ) + sources
        val compiler = K2JVMCompiler()
        val parsed = compiler.createArguments()
        org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments(args.toList(), parsed)
        val code = compiler.exec(collector, Services.EMPTY, parsed)
        out.deleteRecursively()
        return code to msgs
    }

    /** Hook diagnostics as "severity:file:line:[Xn]", sorted by line. */
    fun List<Msg>.hooks() = filter { it.text.startsWith("[") }
        .sortedBy { it.line }
        .map { "${it.severity.presentableName}:${it.file}:${it.line}:${it.text.substring(0, 4)}" }

    /** Expected diagnostics from `// E1 error`, `// E1 warning`, `// E2 error`, `// B5 warning` markers in a fixture. */
    fun expected(fixture: String): List<String> = File(dir, fixture).readLines().mapIndexedNotNull { i, line ->
        Regex("""// (E1|E2|B5) (error|warning)""").find(line)?.let { "${it.groupValues[2]}:$fixture:${i + 1}:[${it.groupValues[1]}]" }
    }

    private fun CompilerMessageSeverity.isErr() = this == CompilerMessageSeverity.ERROR || this == CompilerMessageSeverity.EXCEPTION
}
