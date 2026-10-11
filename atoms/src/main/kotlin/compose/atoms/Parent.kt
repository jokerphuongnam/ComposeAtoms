package compose.atoms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

class ParentScope(
    private val values: Map<String, Any> = emptyMap(),
) {
    fun <T : Any> setting(type: String, value: T): ParentScope {
        return ParentScope(values + (type to value))
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(type: String): T? = values[type] as T?
}

val LocalParentScope = staticCompositionLocalOf { ParentScope() }

/**
 * Publishes [value] to this composable's children, like Jotai's Provider.
 * [staticCompositionLocalOf] does not watch fields inside [value]. Children that read an [Atom] still subscribe to that atom.
 * A sibling outside this call does not see [value].
 */
@Composable
inline fun <reified T : Any> ParentState(
    value: T,
    noinline content: @Composable () -> Unit,
) {
    val next = LocalParentScope.current.setting(T::class.java.name, value)
    CompositionLocalProvider(LocalParentScope provides next, content = content)
}

/** The parent value of type [T]. Missing parent is a caller error. */
@Composable
inline fun <reified T : Any> rememberParentState(): T {
    return LocalParentScope.current.get(T::class.java.name)
        ?: error("No ${T::class.java.simpleName} above this composable. Wrap it in ParentState.")
}
