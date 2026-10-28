package compose.hooks.query

import androidx.compose.runtime.Composable
import compose.hooks.RememberContext
import compose.hooks.Provider
import compose.hooks.rememberContextOf
import compose.hooks.rememberContext

private val parentContexts = HashMap<String, RememberContext<Any>>()

/** The core [RememberContext] used for [T] by [ParentState] / [rememberParentState]. */
@Suppress("UNCHECKED_CAST")
fun <T : Any> parentContext(type: String): RememberContext<T> = synchronized(parentContexts) {
    parentContexts.getOrPut(type) { rememberContextOf<Any>("ParentState<$type>") } as RememberContext<T>
}

/**
 * Publishes [value] to this composable's children, like Jotai's Provider. A sibling outside this call does not see
 * [value]. Built on core `rememberContext`: the value is lent — it lives with the provider's owner, a child keeps
 * it only while the child's owner lives, and reading it after the provider's owner was cleared fails fast.
 *
 * Note: core `rememberContextOf<T>()` + `Provider` + `rememberContext` cover the same need with an explicit,
 * typed context object (several contexts of one type are possible).
 */
@Composable
inline fun <reified T : Any> ParentState(
    value: T,
    noinline content: @Composable () -> Unit,
) {
    parentContext<T>(T::class.java.name).Provider(value, content)
}

/** The parent value of type [T]. Missing parent is a caller error ([compose.hooks.MissingProviderException]). */
@Composable
inline fun <reified T : Any> rememberParentState(): T = rememberContext(parentContext<T>(T::class.java.name))
