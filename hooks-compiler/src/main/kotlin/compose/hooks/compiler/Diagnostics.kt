package compose.hooks.compiler

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.warning1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap

object HooksErrors {
    /** E1: slot hook under if/when/loop/try inside a customRemember block */
    val HOOK_CALLED_CONDITIONALLY by error1<PsiElement, String>()
    /** E1: a custom hook (remember*) called in a loop inside customRemember without key() */
    val HOOK_IN_LOOP_WITHOUT_KEY by warning1<PsiElement, String>()
    /** E2 (same file): rememberContext of a file-private context that is never provided and has no default */
    val CONTEXT_WITHOUT_PROVIDER by error1<PsiElement, String>()
    /** B5: a lent context value captured by work that outlives the screen */
    val LENT_CONTEXT_ESCAPES by warning1<PsiElement, String>()

    init {
        RootDiagnosticRendererFactory.registerFactory(HooksErrorMessages)
    }
}

object HooksErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP = KtDiagnosticFactoryToRendererMap("ComposeHooks").also { map ->
        map.put(
            HooksErrors.HOOK_CALLED_CONDITIONALLY,
            "[E1] Hook ''{0}'' is called conditionally (if/when/loop/try) inside customRemember. Slot hooks must run " +
                "unconditionally and in the same order on every pass; move it into its own customRemember '{ }' or a custom hook.",
            CommonRenderers.STRING,
        )
        map.put(
            HooksErrors.HOOK_IN_LOOP_WITHOUT_KEY,
            "[E1] Custom hook ''{0}'' is called in a loop without key(...): its state follows the position, not the item.",
            CommonRenderers.STRING,
        )
        map.put(
            HooksErrors.CONTEXT_WITHOUT_PROVIDER,
            "[E2] Context ''{0}'' is private to this file, has no default and is never provided here (.Provider). " +
                "rememberContext will throw MissingProviderException.",
            CommonRenderers.STRING,
        )
        map.put(
            HooksErrors.LENT_CONTEXT_ESCAPES,
            "[B5] ''{0}'' is a lent context value; capturing it in work that outlives the screen keeps the provider''s " +
                "object alive after its owner is cleared. Copy what you need or use the screen''s coroutine scope.",
            CommonRenderers.STRING,
        )
    }
}
