package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A typed context (React `createContext`). Values are *lent*:
 * - the provider's lease lives as long as the provider's own hook scope (its owner);
 * - a consumer keeps its reference only while the consumer's owner lives, and drops it when cleared;
 * - when the provider's owner is cleared the lease is revoked, and a consumer that still reads it fails fast
 *   instead of silently keeping a dead screen's object alive.
 */
class RememberContext<T> internal constructor(val name: String, internal val default: (() -> T)?) {
    internal val local: ProvidableCompositionLocal<Lease<T>?> = staticCompositionLocalOf { null }
    override fun toString(): String = "RememberContext($name)"
}

fun <T> rememberContextOf(name: String = "RememberContext", default: (() -> T)? = null): RememberContext<T> =
    RememberContext(name, default)

/** Thrown when a context is read without a provider and has no default (compile-time check E2 covers the same file). */
class MissingProviderException(context: RememberContext<*>) :
    IllegalStateException("No provider for $context. Wrap the caller in $context.Provider(value) { ... } or give it a default.")

/** Thrown when a lent value is read after its provider's owner was cleared. */
class RevokedContextException(context: RememberContext<*>) :
    IllegalStateException("$context was read after its provider's owner was cleared (the lent value is gone).")

internal class Lease<T>(val context: RememberContext<T>, initial: T) {
    private val state: MutableState<T> = mutableStateOf(initial, referentialEqualityPolicy())
    var revoked = false
        private set
    var consumers = 0
    var value: T
        get() {
            if (revoked) throw RevokedContextException(context)
            return state.value
        }
        set(v) { if (!revoked) state.value = v }

    fun revoke() { revoked = true }
}

internal class ConsumerRef<T> {
    var lease: Lease<T>? = null
        set(v) {
            if (field === v) return
            field?.let { it.consumers-- }
            v?.let { it.consumers++ }
            field = v
        }
}

/**
 * Provides [value] to [content]. The provider's hook scope owns the lease: it is revoked when that scope is cleared.
 */
@Composable
fun <T> RememberContext<T>.Provider(value: T, content: @Composable () -> Unit) {
    val lease = withRememberScope("Provider($name)") { scope ->
        scope.slot { Lease(this, value).also { l -> scope.onClear { l.revoke() } } }
    }
    lease.value = value
    CompositionLocalProvider(local provides lease, content = content)
}

/**
 * Reads the nearest provided value of [context] (React `useContext`). The consumer holds the lease only while its
 * own owner lives. Do not capture the returned value in work that outlives the screen (GlobalScope, threads); the
 * compiler plugin warns about that (B5).
 */
@Composable
fun <T> rememberContext(context: RememberContext<T>): T {
    val lease = context.local.current
    return withRememberScope("rememberContext(${context.name})") { scope ->
        val ref = scope.slot { ConsumerRef<T>().also { r -> scope.onClear { r.lease = null } } }
        if (lease == null) {
            ref.lease = null
            context.default?.invoke() ?: throw MissingProviderException(context)
        } else {
            ref.lease = lease
            lease.value
        }
    }
}

/** Test / inspection helpers. */
internal fun <T> RememberContext<T>.debugCurrentLease(ref: Any?): Any? = (ref as? ConsumerRef<*>)?.lease
