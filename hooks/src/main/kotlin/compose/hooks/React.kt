package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Reducer state (React `useReducer`). [dispatch] is the same instance for the hook's lifetime. */
class ReducerState<S, A> @PublishedApi internal constructor(initial: S) {
    internal val state: MutableState<S> = mutableStateOf(initial, structuralEqualityPolicy())
    @PublishedApi internal var reducer: (S, A) -> S = { s, _ -> s }
    val value: S get() = state.value
    val dispatch: (A) -> Unit = { action ->
        Snapshot.withMutableSnapshot { state.value = reducer(state.value, action) }
    }
    operator fun component1(): S = value
    operator fun component2(): (A) -> Unit = dispatch
}

@Composable
inline fun <S, A> rememberReducer(noinline reducer: (S, A) -> S, init: () -> S): ReducerState<S, A> =
    withHookScope("rememberReducer") { scope ->
        scope.slot { ReducerState<S, A>(init()) }.also { it.reducer = reducer }
    }

@PublishedApi internal class MemoBox<T>(var deps: Array<out Any?>, var value: T)

/** Memoized value (React `useMemo`): recomputed only when [deps] change (`contentEquals`). */
@Composable
inline fun <T> rememberMemo(vararg deps: Any?, compute: () -> T): T =
    withHookScope("rememberMemo") { scope ->
        val box = scope.slot { MemoBox(deps, compute()) }
        if (!box.deps.contentEquals(deps)) {
            box.value = compute()
            box.deps = deps
        }
        box.value
    }

/** Mutable, non-observable reference (React `useRef`). Writing [current] never recomposes. */
class Ref<T>(var current: T)

@Composable
inline fun <T> rememberRef(init: () -> T): Ref<T> =
    withHookScope("rememberRef") { scope -> scope.slot { Ref(init()) } }

/** Always holds the latest [value] (for long-lived callbacks / effects that must not restart). */
@Composable
fun <T> rememberUpdated(value: T): State<T> =
    withHookScope("rememberUpdated") { scope ->
        scope.slot { mutableStateOf(value) }.also { it.value = value }
    }

/**
 * Collects [flow] in the hook scope. Collection starts after the first composition, restarts when [flow] or [keys]
 * change and stops when the owner is cleared (not when the screen is hidden).
 */
@Composable
fun <T> rememberFlow(flow: Flow<T>, initial: T, vararg keys: Any?): State<T> =
    withHookScope("rememberFlow") { scope ->
        val state = scope.slot { mutableStateOf(initial) }
        rememberEffect(flow, *keys) {
            launch { flow.collect { state.value = it } }
        }
        state
    }

@PublishedApi internal val idCounter = AtomicLong()

/** Stable unique id for this hook instance (React `useId`), stable across recomposition, reshow and rotation. */
@Composable
fun rememberId(prefix: String = "h"): String =
    withHookScope("rememberId") { scope -> scope.slot { prefix + idCounter.incrementAndGet() } }
