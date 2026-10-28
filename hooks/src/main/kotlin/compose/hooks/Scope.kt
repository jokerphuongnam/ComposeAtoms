package compose.hooks

import androidx.collection.MutableIntObjectMap
import androidx.collection.MutableIntSet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The state container behind [customRemember]. One scope per hook instance.
 *
 * - A scope created at the top of a composable lives in the [ViewModel] of the nearest
 *   [LocalViewModelStoreOwner] (a navigation back-stack entry, an Activity, a Fragment). It survives recomposition,
 *   reshow (back navigation) and configuration changes, and is cleared when that owner is cleared (pop / finish).
 * - A hook called from inside another hook in the same restart scope becomes a child scope keyed by its call site
 *   ([currentCompositeKeyHash]); children are cleared with their parent, and a child that is not called during a
 *   parent pass (condition turned false, `key()` changed) is cleared at the end of that pass.
 * - Without an owner (Preview / inspection) the top scope is held by `remember` and cleared when it is forgotten.
 *
 * Clear order: children first (reverse creation order), then this scope's effects in reverse declaration order
 * (job cancelled, then cleanup), then its coroutine scope, then its slots.
 */
class RememberScope internal constructor(@PublishedApi internal val name: String) {
    @PublishedApi internal val slots = ArrayList<Any?>(4)
    @PublishedApi internal var index = 0
    @PublishedApi internal var sealed = false
    @PublishedApi internal var rs: RecomposeScope? = null
    @PublishedApi internal var passParent: RememberScope? = null
    private var children: MutableIntObjectMap<RememberScope>? = null
    private var childOrder: ArrayList<Int>? = null
    private var seen: MutableIntSet? = null
    private var occurrences: MutableIntObjectMap<Int>? = null
    internal var effects: ArrayList<EffectState>? = null
    internal var releases: ArrayList<() -> Unit>? = null
    private var coroutines: CoroutineScope? = null

    /** true once the owner (or the parent pass) cleared this scope; any further use is a bug in the caller */
    var isCleared: Boolean = false
        private set

    /** Lazily created scope bound to this hook instance (like viewModelScope): cancelled when the scope is cleared. */
    val coroutineScope: CoroutineScope
        get() {
            check(!isCleared) { "RememberScope '$name' was cleared; it cannot start new work" }
            return coroutines ?: CoroutineScope(
                SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Hooks.onError(e) },
            ).also { coroutines = it }
        }

    @PublishedApi internal fun beginPass(rs: RecomposeScope, parent: RememberScope?) {
        check(!isCleared) { "RememberScope '$name' was cleared but is being composed again" }
        this.rs = rs
        passParent = parent
        index = 0
        seen?.clear()
        occurrences?.clear()
    }

    @PublishedApi internal fun endPass() {
        if (!sealed) {
            sealed = true
            slots.trimToSize()
        } else if (index != slots.size) {
            throw IllegalStateException(
                "Hook '$name' called $index slot hooks in this pass but ${slots.size} before. Slot hooks " +
                    "(rememberState, rememberEffect, rememberRef, ...) must be called unconditionally and in the same " +
                    "order on every pass. Move conditional ones into their own customRemember { } or a key(...) { }.",
            )
        }
        pruneChildren()
    }

    @PublishedApi internal fun finishPass() {
        rs = null
        passParent = null
    }

    @Suppress("UNCHECKED_CAST")
    @PublishedApi internal inline fun <T> slot(create: () -> T): T {
        val i = index++
        if (i == slots.size) {
            if (sealed) {
                throw IllegalStateException(
                    "Hook '$name' called more slot hooks than in its first pass. Slot hooks must be called " +
                        "unconditionally and in the same order on every pass.",
                )
            }
            val value = create()
            slots.add(value)
            return value
        }
        return slots[i] as T
    }

    @PublishedApi internal fun child(key: Int, name: String): RememberScope {
        val seenKeys = seen ?: MutableIntSet().also { seen = it }
        var id = key
        if (!seenKeys.add(id)) {
            // same call site more than once in one pass (a loop without key()): positional occurrence
            val occ = occurrences ?: MutableIntObjectMap<Int>().also { occurrences = it }
            val n = (occ[key] ?: 0) + 1
            occ[key] = n
            id = key * 31 + n
            while (!seenKeys.add(id)) id++
        }
        val map = children ?: MutableIntObjectMap<RememberScope>(4).also { children = it }
        return map[id] ?: RememberScope(name).also {
            map[id] = it
            (childOrder ?: ArrayList<Int>(4).also { o -> childOrder = o }).add(id)
        }
    }

    private fun pruneChildren() {
        val map = children ?: return
        val seenKeys = seen
        if (seenKeys != null && map.size == seenKeys.size) return
        val order = childOrder ?: return
        for (i in order.indices.reversed()) {
            val id = order[i]
            if (seenKeys == null || !seenKeys.contains(id)) {
                map.remove(id)?.clear()
                order.removeAt(i)
            }
        }
    }

    internal fun addEffect(e: EffectState) {
        (effects ?: ArrayList<EffectState>(2).also { effects = it }).add(e)
    }

    /** Registers work that runs when this scope is cleared (after effects). Used by context providers and :query. */
    fun onClear(release: () -> Unit) {
        check(!isCleared) { "RememberScope '$name' was already cleared" }
        (releases ?: ArrayList<() -> Unit>(2).also { releases = it }).add(release)
    }

    /** Clears this scope and its subtree. Idempotent. Errors from cleanups are collected and reported once. */
    fun clear() {
        if (isCleared) return
        isCleared = true
        var error: Throwable? = null
        fun guard(block: () -> Unit) {
            try { block() } catch (t: Throwable) { if (error == null) error = t else error!!.addSuppressed(t) }
        }
        val order = childOrder
        val map = children
        if (order != null && map != null) for (i in order.indices.reversed()) map[order[i]]?.let { c -> guard { c.clear() } }
        children = null; childOrder = null
        effects?.let { list -> for (i in list.indices.reversed()) guard { list[i].dispose() } }
        effects = null
        coroutines?.cancel(); coroutines = null
        releases?.let { list -> for (i in list.indices.reversed()) guard { list[i]() } }
        releases = null
        slots.clear()
        rs = null; passParent = null
        error?.let { Hooks.onError(it) }
    }

    /** Test/inspection only. */
    fun debugChildCount(): Int = children?.size ?: 0
    fun debugSlotCount(): Int = slots.size
}

/** Global configuration. */
object Hooks {
    /**
     * Receives errors thrown by effect cleanups, effect coroutines and flow collectors.
     * Default: rethrow on the current thread (same outcome as an exception in LaunchedEffect / DisposableEffect).
     */
    @JvmStatic var onError: (Throwable) -> Unit = { throw it }
}

/** Composition is single-threaded per Recomposer; the active hook scope is tracked on the composing thread. */
@PublishedApi
internal object ScopeStack {
    private val local = ThreadLocal<RememberScope?>()
    @PublishedApi internal fun peek(): RememberScope? = local.get()
    @PublishedApi internal fun push(scope: RememberScope) = local.set(scope)
    @PublishedApi internal fun pop(parent: RememberScope?) = local.set(parent)
}

/** One per ViewModelStoreOwner: holds the top-level scopes of every composable under that owner. */
internal class HooksViewModel : ViewModel() {
    private val tops = MutableIntObjectMap<RememberScope>()
    private val order = ArrayList<Int>()
    fun top(key: Int, name: String): RememberScope =
        tops[key] ?: RememberScope(name).also { tops[key] = it; order.add(key) }
    fun topCount(): Int = tops.size
    public override fun onCleared() {
        var error: Throwable? = null
        val previous = Hooks.onError
        Hooks.onError = { e -> if (error == null) error = e else error!!.addSuppressed(e) }
        try {
            for (i in order.indices.reversed()) tops[order[i]]?.clear()
        } finally {
            Hooks.onError = previous
        }
        tops.clear(); order.clear()
        error?.let { previous(it) }
    }

    companion object {
        const val KEY = "compose.hooks.HooksViewModel"
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = HooksViewModel() as T
        }
    }
}

internal class InspectionScope(val scope: RememberScope) : RememberObserver {
    override fun onRemembered() {}
    override fun onForgotten() = scope.clear()
    override fun onAbandoned() = scope.clear()
}

@PublishedApi
@Composable
internal fun topScope(key: Int, name: String): RememberScope {
    val owner: ViewModelStoreOwner? = LocalViewModelStoreOwner.current
    // one remember for both cases: no early return, so no extra replace groups
    val holder: Any = remember(owner, key) {
        if (owner == null) InspectionScope(RememberScope(name))
        else ViewModelProvider.create(owner.viewModelStore, HooksViewModel.Factory)[HooksViewModel.KEY, HooksViewModel::class].top(key, name)
    }
    return if (holder is InspectionScope) holder.scope else holder as RememberScope
}

/** Number of top-level hook scopes held for [owner] (tests / debugging). */
fun debugTopScopeCount(owner: ViewModelStoreOwner): Int =
    ViewModelProvider.create(owner.viewModelStore, HooksViewModel.Factory)[HooksViewModel.KEY, HooksViewModel::class].topCount()

/**
 * Defines a custom hook. State created by the hooks called inside [block] lives as long as the owner of this
 * call site (see [RememberScope]); it is not reset by recomposition, back navigation or rotation.
 *
 * ```
 * @Composable
 * fun rememberCounter(): Counter = customRemember {
 *     var count by rememberState { 0 }
 *     Counter(count) { count++ }
 * }
 * ```
 * @param key optional explicit identity; by default the call site ([currentCompositeKeyHash]) is the identity.
 */
@Composable
inline fun <R> customRemember(key: Any? = null, name: String = "customRemember", block: () -> R): R {
    val parent = ScopeStack.peek()
    val hash = if (key == null) currentCompositeKeyHash else key.hashCode()
    val rs = currentRecomposeScope
    // Same restart scope as the active hook -> child. Otherwise (top of a composable, or a nested hook that is itself a
    // restartable composable and can recompose alone) -> owner-level scope keyed by call site, which is the same
    // scope whether it is reached from the parent's pass or from its own restart (EDGE fix).
    val scope = if (parent != null && parent.rs === rs) parent.child(hash, name) else topScope(hash, name)
    scope.beginPass(rs, parent)
    ScopeStack.push(scope)
    try {
        return block().also { scope.endPass() }
    } finally {
        scope.finishPass()
        ScopeStack.pop(parent)
    }
}

/** The scope of the innermost [customRemember] on this restart scope, or a fresh implicit one for a bare hook call. */
@PublishedApi
@Composable
internal fun currentRememberScope(): RememberScope? {
    val active = ScopeStack.peek()
    return if (active != null && active.rs === currentRecomposeScope) active else null
}

/**
 * Runs [block] with the hook scope of the caller: the active [customRemember] scope when called inside one,
 * otherwise an implicit single-hook scope for this call site (so a bare `rememberState { }` in a screen also
 * survives back navigation and rotation).
 */
@Composable
@PublishedApi
internal inline fun <R> withRememberScope(name: String, block: (RememberScope) -> R): R {
    val active = currentRememberScope()
    return if (active != null) block(active) else customRemember(name = name) { block(ScopeStack.peek()!!) }
}
