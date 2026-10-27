package compose.hooks.compiler

import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.Assert.assertEquals
import org.junit.Test

class FixtureTest {
    data class Msg(val severity: CompilerMessageSeverity, val file: String, val line: Int, val text: String)

    private fun compile(vararg fixtures: String): Pair<ExitCode, List<Msg>> {
        val dir = File(javaClass.classLoader.getResource("fixtures/Stubs.kt")!!.toURI()).parentFile
        val sources = (listOf("Stubs.kt", "StubsRuntime.kt", "StubsCoroutines.kt") + fixtures).map { File(dir, it).path }
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

    private fun List<Msg>.hooks() = filter { it.text.startsWith("[") }.map { "${it.severity.presentableName}:${it.file}:${it.line}:${it.text.substring(0, 4)}" }

    @Test fun e1AllowedPatternsCompileClean() {
        val (code, msgs) = compile("E1_ok.kt")
        println(msgs.joinToString("\n"))
        assertEquals(emptyList<String>(), msgs.hooks())
        assertEquals(ExitCode.OK, code)
    }

    @Test fun e1ViolationsReported() {
        val (code, msgs) = compile("E1_bad.kt")
        println(msgs.joinToString("\n"))
        assertEquals(
            listOf(
                "error:E1_bad.kt:7:[E1]", "error:E1_bad.kt:8:[E1]", "error:E1_bad.kt:9:[E1]",
                "error:E1_bad.kt:10:[E1]", "error:E1_bad.kt:11:[E1]",
            ),
            msgs.hooks().sortedBy { it.split(":")[2].toInt() },
        )
        assertEquals(ExitCode.COMPILATION_ERROR, code)
    }

    @Test fun e1CustomHookInLoopWithoutKeyWarns() {
        val (code, msgs) = compile("E1_warn.kt")
        println(msgs.joinToString("\n"))
        assertEquals(listOf("warning:E1_warn.kt:7:[E1]", "warning:E1_warn.kt:8:[E1]"), msgs.hooks().sorted())
        assertEquals(ExitCode.OK, code)
    }

    @Test fun e2MissingProviderSameFile() {
        val (_, msgs) = compile("E2.kt")
        println(msgs.joinToString("\n"))
        assertEquals(listOf("error:E2.kt:10:[E2]"), msgs.hooks())
    }

    @Test fun b5LendingLeakWarnings() {
        val (code, msgs) = compile("B5.kt")
        println(msgs.joinToString("\n"))
        assertEquals(listOf("warning:B5.kt:12:[B5]", "warning:B5.kt:13:[B5]", "warning:B5.kt:14:[B5]"), msgs.hooks().sorted())
        assertEquals(ExitCode.OK, code)
    }
}

private fun CompilerMessageSeverity.isErr() = this == CompilerMessageSeverity.ERROR || this == CompilerMessageSeverity.EXCEPTION
