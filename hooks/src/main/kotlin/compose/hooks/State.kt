package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.structuralEqualityPolicy

/**
 * Hook state (React `useState`). Created once per hook instance and kept for the owner's lifetime.
 *
 * - [init] runs only on the first pass.
 * - Writing a value equal to the current one (per [policy]) does not invalidate readers (skip on equal).
 * - Several writes in the same event are batched into one recomposition (snapshot semantics).
 * - Functional updates: `state.update { it + 1 }`. Destructuring: `val (count, setCount) = rememberState { 0 }`.
 *
 * ```
 * var count by rememberState { 0 }
 * ```
 */
@Composable
inline fun <T> rememberState(
    policy: SnapshotMutationPolicy<T> = structuralEqualityPolicy(),
    init: () -> T,
): MutableState<T> = withHookScope("rememberState") { scope -> scope.slot { mutableStateOf(init(), policy) } }

/**
 * Value form: `var name by rememberState("guest")`. Only the first value is stored.
 *
 * Without the ComposeHooks compiler plugin the argument expression is still *evaluated* on every pass (Kotlin
 * evaluates arguments eagerly) and the result is ignored after the first pass; use the lambda form for expensive
 * initialisers. (The planned plugin rewrite of the value form into the lambda form is not implemented yet.)
 */
@Composable
@JvmName("rememberStateValue")
fun <T> rememberState(value: T): MutableState<T> =
    withHookScope("rememberState") { scope -> scope.slot { mutableStateOf(value, structuralEqualityPolicy()) } }

/** Functional updater (React `setState(prev => next)`); atomic w.r.t. other snapshot writes on the same thread. */
inline fun <T> MutableState<T>.update(transform: (T) -> T) {
    value = transform(value)
}
