package compose.hooks.compiler

import compose.hooks.compiler.Fixtures.compile
import compose.hooks.compiler.Fixtures.expected
import compose.hooks.compiler.Fixtures.hooks
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RulesOfHooksChecker (E1), MissingProviderChecker (E2), LendingLeakChecker (B5) on fixture sources. */
class CheckersTest {
    private fun check(fixture: String, exit: ExitCode) {
        val (code, msgs) = compile(fixture)
        println(msgs.joinToString("\n"))
        assertEquals(expected(fixture), msgs.hooks())
        assertEquals("no unrelated compile errors", exit, code)
        if (exit == ExitCode.COMPILATION_ERROR) {
            assertTrue("only hook errors", msgs.filter { it.severity.isError }.all { it.text.startsWith("[") })
        }
    }

    // E1 HOOK_CALLED_CONDITIONALLY / HOOK_IN_LOOP_WITHOUT_KEY
    @Test fun e1AllowedPatternsCompileClean() {
        val (code, msgs) = compile("E1_ok.kt")
        assertEquals(emptyList<String>(), msgs.hooks())
        assertEquals(ExitCode.OK, code)
    }

    @Test fun e1ViolationsReported() {
        assertEquals(
            listOf("error:E1_bad.kt:7:[E1]", "error:E1_bad.kt:8:[E1]", "error:E1_bad.kt:9:[E1]", "error:E1_bad.kt:10:[E1]", "error:E1_bad.kt:11:[E1]"),
            expected("E1_bad.kt"),
        )
        check("E1_bad.kt", ExitCode.COMPILATION_ERROR)
    }

    @Test fun e1CustomHookInLoopWithoutKeyWarns() {
        assertEquals(listOf("warning:E1_warn.kt:7:[E1]", "warning:E1_warn.kt:8:[E1]"), expected("E1_warn.kt"))
        check("E1_warn.kt", ExitCode.OK)
    }

    @Test fun e1MoreViolations_repeatWhileNamedArgQueryHooksLoopConditionCatch() = check("E1_more_bad.kt", ExitCode.COMPILATION_ERROR)

    @Test fun e1MoreAllowedPatterns() {
        assertEquals(emptyList<String>(), expected("E1_more_ok.kt"))
        check("E1_more_ok.kt", ExitCode.OK)
    }

    @Test fun e1MoreCustomHookWarnings() = check("E1_more_warn.kt", ExitCode.OK)

    // E2 CONTEXT_WITHOUT_PROVIDER
    @Test fun e2MissingProviderSameFile() {
        assertEquals(listOf("error:E2.kt:10:[E2]"), expected("E2.kt"))
        check("E2.kt", ExitCode.COMPILATION_ERROR)
    }

    @Test fun e2OnlyFilePrivateUndefaultedUnprovidedContexts() = check("E2_more.kt", ExitCode.COMPILATION_ERROR)

    // B5 LENT_CONTEXT_ESCAPES
    @Test fun b5LendingLeakWarnings() {
        assertEquals(listOf("warning:B5.kt:12:[B5]", "warning:B5.kt:13:[B5]", "warning:B5.kt:14:[B5]"), expected("B5.kt"))
        check("B5.kt", ExitCode.OK)
    }

    @Test fun b5MoreEscapesAndNonEscapes() = check("B5_more.kt", ExitCode.OK)

    @Test fun debugPropertyTracesTheContainingChain() {
        val err = System.err
        val buffer = ByteArrayOutputStream()
        System.setProperty("hooks.debug", "1")
        System.setErr(PrintStream(buffer, true))
        try {
            compile("E1_warn.kt")
        } finally {
            System.setErr(err)
            System.clearProperty("hooks.debug")
        }
        assertTrue(buffer.toString().lines().any { it.startsWith("HOOKSDBG rememberRow ") && "FirAnonymousFunction" in it })
    }
}
