package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/** Receiver of a [rememberEffect] block: a [CoroutineScope] whose Job is cancelled before the cleanup runs. */
class EffectScope internal constructor(private val owner: RememberScope) : CoroutineScope {
    private var context: CoroutineContext? = null
    override val coroutineContext: CoroutineContext
        get() = context ?: owner.coroutineScope.coroutineContext.let { parent ->
            parent + Job(parent[Job])
        }.also { context = it }

    internal fun cancelJob() {
        context?.let { it.cancel() }
        context = null
    }
}

internal class EffectState(private val owner: RememberScope) {
    var deps: Array<out Any?>? = null
    var cleanup: Function0<*>? = null
    var scope: EffectScope? = null
    var pendingDeps: Array<out Any?>? = null
    var pendingBlock: (EffectScope.() -> Any?)? = null

    fun needsRun(next: Array<out Any?>): Boolean {
        val committed = deps ?: return true
        return !committed.contentEquals(next)
    }

    fun runPending() {
        val block = pendingBlock ?: return
        val next = pendingDeps
        pendingBlock = null
        pendingDeps = null
        if (owner.isCleared) return
        var error: Throwable? = null
        if (deps != null) {
            try { dispose() } catch (t: Throwable) { error = t }
        }
        deps = next
        val s = EffectScope(owner)
        scope = s
        val result = try { s.block() } catch (t: Throwable) {
            if (error == null) error = t else error.addSuppressed(t)
            null
        }
        cleanup = result as? Function0<*>
        error?.let { Hooks.onError(it) }
    }

    /** cancel the effect's Job first, then run its cleanup */
    fun dispose() {
        scope?.cancelJob()
        scope = null
        val c = cleanup
        cleanup = null
        c?.invoke()
    }
}

/**
 * Effect with optional cleanup (React `useEffect`), owned by the hook scope.
 *
 * - Runs after the first successful composition (in a [SideEffect]; abandoned compositions never run it).
 * - Re-runs when any of [deps] changes (`contentEquals`); no deps = run once for the owner's lifetime.
 * - If the block's result is a function, that function is the cleanup. It runs before the next re-run and when the
 *   owner is cleared (pop / finish), but NOT when the screen is merely hidden (back stack) or recreated (rotation).
 * - `launch { }` inside the block uses a Job that is cancelled right before the cleanup.
 * - Clear order: children before parents, effects in reverse declaration order.
 *
 * ```
 * rememberEffect(userId) {
 *     val sub = repo.subscribe(userId)
 *     launch { repo.refresh(userId) }
 *     { sub.close() }            // cleanup
 * }
 * ```
 * Caution: a block whose last expression is accidentally a function value treats it as cleanup.
 */
@Composable
@NonRestartableComposable
fun <R> rememberEffect(vararg deps: Any?, block: EffectScope.() -> R) {
    withHookScope("rememberEffect") { scope ->
        val effect = scope.slot { EffectState(scope).also { scope.addEffect(it) } }
        if (effect.needsRun(deps)) {
            effect.pendingDeps = deps
            effect.pendingBlock = block
            SideEffect { effect.runPending() }
        }
    }
}
