package compose.hooks.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HooksErrors factories and the rendered HooksErrorMessages texts. */
class DiagnosticsTest {
    @Test fun factoriesHaveSeverityAndNames() {
        assertEquals("HOOK_CALLED_CONDITIONALLY", HooksErrors.HOOK_CALLED_CONDITIONALLY.name)
        assertEquals("HOOK_IN_LOOP_WITHOUT_KEY", HooksErrors.HOOK_IN_LOOP_WITHOUT_KEY.name)
        assertEquals("CONTEXT_WITHOUT_PROVIDER", HooksErrors.CONTEXT_WITHOUT_PROVIDER.name)
        assertEquals("LENT_CONTEXT_ESCAPES", HooksErrors.LENT_CONTEXT_ESCAPES.name)
        assertEquals("ComposeHooks", HooksErrorMessages.MAP.name)
    }

    private fun rendered(fixture: String) = Fixtures.compile(fixture).second.filter { it.text.startsWith("[") }.map { it.text }

    @Test fun messagesNameTheHookOrContext() {
        assertTrue(rendered("E1_bad.kt").contains(
            "[E1] Hook 'rememberState' is called conditionally (if/when/loop/try) inside customRemember. Slot hooks must run " +
                "unconditionally and in the same order on every pass; move it into its own customRemember { } or a custom hook.",
        ))
        assertTrue(rendered("E1_warn.kt").contains(
            "[E1] Custom hook 'rememberRow' is called in a loop without key(...): its state follows the position, not the item.",
        ))
        assertTrue(rendered("E2.kt").single().startsWith("[E2] Context 'Missing' is private to this file, has no default"))
        assertTrue(rendered("B5.kt").all { it.startsWith("[B5] 'session' is a lent context value;") && "provider's" in it })
    }
}
