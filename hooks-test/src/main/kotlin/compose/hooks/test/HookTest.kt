package compose.hooks.test

import android.view.View
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import compose.hooks.Hooks
import compose.hooks.Provider
import compose.hooks.RememberContext
import compose.hooks.debugTopScopeCount
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Wraps the content of every [HookTestScope.renderHook] (providers, themes, a QueryClientProvider…). */
typealias HookWrapper = @Composable (content: @Composable () -> Unit) -> Unit

private object UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

/** Observes the owner's clear without adding a hook scope to it (hookScopeCount stays the hook's own). */
private class ClearProbe : ViewModel() {
    var onClear: () -> Unit = {}

    override fun onCleared() {
        onClear()
    }
}

private object ClearProbeFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ClearProbe() as T
}

/** Frame clock on virtual time: a frame is granted as soon as the Recomposer asks. */
private class VirtualFrameClock(private val scheduler: TestCoroutineScheduler) : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
        onFrame(scheduler.currentTime * 1_000_000)
}

/**
 * A navigation back-stack entry stand-in ([ViewModelStoreOwner]): hook state lives here.
 * [pop] = the entry is destroyed (its ViewModelStore is cleared): hook cleanups run, scopes are cancelled.
 */
class BackStackEntry internal constructor(private val host: HookTestHost) : ViewModelStoreOwner {
    /** The entry's ViewModelStore (hook scopes live here). */
    override val viewModelStore: ViewModelStore = ViewModelStore()

    /** True after [pop]. */
    var isPopped: Boolean = false
        private set

    /** Top-level hook scopes alive on this entry; 0 after [pop] = nothing leaked. */
    val hookScopeCount: Int get() = debugTopScopeCount(this)

    /** Navigation order: hide every composition shown for this entry, then destroy the entry. Idempotent. */
    fun pop() {
        if (isPopped) return
        host.results.filter { it.owner === this && it.isShown }.forEach { it.hide() }
        isPopped = true
        viewModelStore.clear()
        host.runCurrent()
    }
}

/**
 * A context value for [HookTestScope.renderHook]'s `provide` (instead of wrapping the hook in `.Provider`).
 *
 * @property context the context provided.
 * @property value the value the hook reads from [context].
 */
class FakeProvision<T> internal constructor(val context: RememberContext<T>, val value: T)

/** `renderHook(provide = listOf(ThemeCtx provides "dark")) { … }` */
infix fun <T> RememberContext<T>.provides(value: T): FakeProvision<T> = FakeProvision(this, value)

/** Same as [provides]: `renderHook(provide = listOf(provide(ThemeCtx, "dark"))) { … }` */
fun <T> provide(context: RememberContext<T>, value: T): FakeProvision<T> = FakeProvision(context, value)

/**
 * A rendered hook. Every committed (re)composition appends the hook's return value to [history]; [current] is the
 * latest. Time only moves when the test moves it ([HookTestScope.advanceTimeBy] / [HookTestScope.advanceUntilIdle]).
 *
 * Turbine-like history assertions: [nextValue] consumes the oldest value not consumed yet, [expectNoValue] /
 * [skipValues] / [ignoreRemaining]. With `strictHistory = true` the test fails if values are left unconsumed.
 */
class HookResult<P, R> internal constructor(
    private val host: HookTestHost,
    /** The owner given to renderHook; null for the no-owner (Preview / inspection) path. */
    val owner: BackStackEntry?,
    initialProps: P,
    private val provisions: List<FakeProvision<*>>,
    private val wrappers: List<HookWrapper>,
    internal val strictHistory: Boolean,
    private val hook: @Composable (P) -> R,
) {
    private val props = mutableStateOf(initialProps)
    private var composition: Composition? = null
    private val committed = ArrayList<R>()
    private var consumed = 0

    /** Committed values, one per successful (re)composition of the hook, oldest first. */
    val history: List<R> get() = committed.toList()

    /** The latest committed value. */
    val current: R get() = committed.lastOrNull() ?: throw AssertionError("the hook has not committed a value yet")

    /** Number of times the hook ran: its first composition plus every recomposition of it. */
    var recomposeCount: Int = 0
        private set

    /** True once the hook's lifetime ended: owner popped, or (no owner) composition disposed. */
    var wasCleared: Boolean = false
        private set

    /** True while a composition is shown ([hide] / pop make it false, [show] true again). */
    val isShown: Boolean get() = composition != null

    init {
        if (owner != null) {
            val probe = ViewModelProvider(owner.viewModelStore, ClearProbeFactory)
                .get("compose.hooks.test.ClearProbe:${System.identityHashCode(this)}", ClearProbe::class.java)
            probe.onClear = { wasCleared = true }
        }
    }

    /** History values not consumed by [nextValue] / [skipValues] yet. */
    val unconsumed: List<R> get() = committed.subList(consumed, committed.size).toList()

    /** Runs [block] as a state mutation, then lets recomposition, effects and due coroutines run (time stays put). */
    fun act(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        host.runCurrent()
    }

    /** New props for the hook (RTL `rerender`). */
    fun rerender(newProps: P) = act { props.value = newProps }

    /** Composition disposed while the owner lives (navigated forward / app in background). */
    fun hide() {
        val c = composition ?: throw IllegalStateException("already hidden")
        c.dispose()
        composition = null
        host.runCurrent()
    }

    /** Composition created again for the same owner (back navigation). */
    fun show() {
        check(composition == null) { "already shown" }
        check(owner?.isPopped != true) { "owner was popped" }
        composition = Composition(UnitApplier, host.recomposer).apply { setContent { Content() } }
        host.runCurrent()
    }

    /** Configuration change: new composition, same owner (for hooks this is hide() + show()). */
    fun recreate() {
        hide()
        show()
    }

    /** Consumes and returns the oldest unconsumed value (runs due work first); fails when there is none. */
    fun nextValue(): R {
        host.runCurrent()
        if (consumed >= committed.size) throw AssertionError("expected a new value, history has none left (current = ${committed.lastOrNull()})")
        return committed[consumed++]
    }

    /** Fails when a value was committed that has not been consumed. */
    fun expectNoValue() {
        host.runCurrent()
        if (consumed < committed.size) throw AssertionError("expected no new value, got ${unconsumed}")
    }

    /** Consumes [count] values. */
    fun skipValues(count: Int) {
        repeat(count) { nextValue() }
    }

    /** Marks every value committed so far as consumed. */
    fun ignoreRemaining() {
        consumed = committed.size
    }

    @Composable
    private fun Content() {
        if (owner != null) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { Wrapped(0) }
        } else {
            // no ViewModelStoreOwner (Preview / inspection): LocalViewModelStoreOwner falls back to LocalView
            CompositionLocalProvider(LocalView provides View(null)) { Wrapped(0) }
        }
    }

    @Composable
    private fun Wrapped(i: Int) {
        if (i < wrappers.size) {
            wrappers[i] { Wrapped(i + 1) }
        } else {
            Provided(0)
        }
    }

    @Composable
    private fun Provided(i: Int) {
        if (i < provisions.size) {
            @Suppress("UNCHECKED_CAST")
            val p = provisions[i] as FakeProvision<Any?>
            p.context.Provider(p.value) { Provided(i + 1) }
            return
        }
        recomposeCount++
        val value = hook(props.value)
        SideEffect { committed += value }
        if (owner == null) {
            // inspection scopes are tied to the composition
            DisposableEffect(Unit) { onDispose { wasCleared = true } }
        }
    }
}

/** What a hook test can do. Created by [runHookTest]; extensions (e.g. query-test) delegate to it. */
interface HookTestScope {
    /** The coroutine test scope of this test (virtual time). */
    val testScope: TestScope

    /** The test's `Dispatchers.Main` (a [StandardTestDispatcher] on [testScope]'s scheduler). */
    val mainDispatcher: TestDispatcher

    /** Virtual time in ms. */
    val currentTime: Long

    /** A new back-stack entry (owner) for one or more [renderHook] calls. */
    fun backStackEntry(): BackStackEntry

    /**
     * Renders [hook] in a composition for [owner] (null = no ViewModelStoreOwner, the Preview path), with [provide]d
     * context values and [wrapper] (plus the test's [addWrapper]s) around it, and runs the first frame.
     */
    fun <R> renderHook(
        owner: BackStackEntry? = backStackEntry(),
        provide: List<FakeProvision<*>> = emptyList(),
        wrapper: HookWrapper? = null,
        strictHistory: Boolean = false,
        hook: @Composable () -> R,
    ): HookResult<Unit, R>

    /** [renderHook] with props: `renderHookWithProps("a") { v -> rememberX(v) }` then `rerender("b")`. */
    fun <P, R> renderHookWithProps(
        initialProps: P,
        owner: BackStackEntry? = backStackEntry(),
        provide: List<FakeProvision<*>> = emptyList(),
        wrapper: HookWrapper? = null,
        strictHistory: Boolean = false,
        hook: @Composable (P) -> R,
    ): HookResult<P, R>

    /** A wrapper applied (outermost first) to every hook rendered afterwards in this test. */
    fun addWrapper(wrapper: HookWrapper)

    /** Runs [block] at teardown, after every composition was disposed and every entry popped (reverse order). */
    fun onTeardown(block: () -> Unit)

    /** Runs the work that is due now (no time advance). */
    fun runCurrent()

    /** Runs everything, advancing virtual time as needed. */
    fun advanceUntilIdle()

    /** Advances virtual time by [ms] and runs what is due at the new time. */
    fun advanceTimeBy(ms: Long)

    /** [advanceTimeBy] in ms. */
    fun advanceTimeBy(duration: Duration) = advanceTimeBy(duration.inWholeMilliseconds)

    /** Errors reported so far (Hooks.onError, composition, hook coroutines); taking them keeps the test green. */
    fun takeErrors(): List<Throwable>
}

internal class HookTestHost(override val testScope: TestScope, override val mainDispatcher: TestDispatcher) : HookTestScope {
    private val clock = VirtualFrameClock(testScope.testScheduler)
    private val errors = ArrayList<Throwable>()

    // NOT backgroundScope: advanceUntilIdle() stops when only background work remains, so recomposition scheduled
    // there would be skipped. A foreground scope outside the TestScope Job: drained by advanceUntilIdle, not awaited.
    val hostScope = CoroutineScope(mainDispatcher + clock + Job() + CoroutineExceptionHandler { _, e -> report(e) })
    val recomposer = Recomposer(hostScope.coroutineContext)
    val results = ArrayList<HookResult<*, *>>()
    private val entries = ArrayList<BackStackEntry>()
    private val wrappers = ArrayList<HookWrapper>()
    private val teardowns = ArrayList<() -> Unit>()

    fun report(e: Throwable) {
        synchronized(errors) { errors += e }
    }

    fun pendingErrors(): List<Throwable> = synchronized(errors) { errors.toList() }

    override val currentTime: Long get() = testScope.testScheduler.currentTime

    override fun backStackEntry(): BackStackEntry = BackStackEntry(this).also { entries += it }

    override fun <R> renderHook(
        owner: BackStackEntry?,
        provide: List<FakeProvision<*>>,
        wrapper: HookWrapper?,
        strictHistory: Boolean,
        hook: @Composable () -> R,
    ): HookResult<Unit, R> = renderHookWithProps(Unit, owner, provide, wrapper, strictHistory) { hook() }

    override fun <P, R> renderHookWithProps(
        initialProps: P,
        owner: BackStackEntry?,
        provide: List<FakeProvision<*>>,
        wrapper: HookWrapper?,
        strictHistory: Boolean,
        hook: @Composable (P) -> R,
    ): HookResult<P, R> {
        val all = if (wrapper == null) wrappers.toList() else wrappers + wrapper
        return HookResult(this, owner, initialProps, provide, all, strictHistory, hook).also {
            results += it
            it.show()
        }
    }

    override fun addWrapper(wrapper: HookWrapper) {
        wrappers += wrapper
    }

    override fun onTeardown(block: () -> Unit) {
        teardowns += block
    }

    override fun runCurrent() = testScope.testScheduler.runCurrent()

    override fun advanceUntilIdle() = testScope.testScheduler.advanceUntilIdle()

    override fun advanceTimeBy(ms: Long) {
        testScope.testScheduler.advanceTimeBy(ms)
        testScope.testScheduler.runCurrent()
    }

    override fun takeErrors(): List<Throwable> = synchronized(errors) { errors.toList().also { errors.clear() } }

    fun start() {
        hostScope.launch { recomposer.runRecomposeAndApplyChanges() }
        runCurrent()
    }

    fun teardown() {
        results.filter { it.isShown }.forEach { it.hide() }
        // pop every live entry: owner-scoped coroutines run on Main (the test dispatcher) outside the TestScope Job,
        // so an endless loop in a hook would otherwise keep runTest's final advanceUntilIdle busy forever
        entries.forEach { it.pop() }
        for (i in teardowns.indices.reversed()) {
            try { teardowns[i]() } catch (t: Throwable) { report(t) }
        }
        recomposer.cancel()
        hostScope.cancel()
    }

    fun strictFailures(): List<Throwable> = results.filter { it.strictHistory && it.unconsumed.isNotEmpty() }
        .map { AssertionError("strictHistory: unconsumed hook values ${it.unconsumed}") }
}

/**
 * One hook test on virtual time:
 * - `Dispatchers.Main` is a [StandardTestDispatcher] of this test, so hook coroutines, effects and the Recomposer run
 *   on virtual time; a frame is granted whenever the Recomposer asks;
 * - errors reported through [Hooks.onError] (effect / cleanup / launch failures), composition errors and failures of
 *   hook coroutines are collected; the test fails if any are left ([HookTestScope.takeErrors] to expect them);
 * - at the end every composition is disposed and every [BackStackEntry] popped (cleanups run), then
 *   [HookTestScope.onTeardown] blocks run.
 *
 * Not for in-JVM parallel tests: `Dispatchers.setMain` and `Hooks.onError` are process-global.
 */
fun runHookTest(timeout: Duration = 10.seconds, body: suspend HookTestScope.() -> Unit) {
    val main = StandardTestDispatcher()
    Dispatchers.setMain(main)
    val previousOnError = Hooks.onError
    try {
        runTest(main, timeout) {
            val host = HookTestHost(this, main)
            Hooks.onError = { host.report(it) }
            val handle = Snapshot.registerGlobalWriteObserver {
                // like AndroidUiDispatcher's GlobalSnapshotManager, on the test dispatcher
                host.hostScope.launch { Snapshot.sendApplyNotifications() }
            }
            host.start()
            try {
                host.body()
                host.runCurrent()
            } finally {
                handle.dispose()
                host.teardown()
            }
            val failures = host.pendingErrors() + host.strictFailures()
            if (failures.isNotEmpty()) {
                val first = failures.first()
                val error = first as? AssertionError
                    ?: AssertionError("hook test reported ${failures.size} error(s) (Hooks.onError / composition / coroutines)", first)
                failures.drop(1).forEach(error::addSuppressed)
                throw error
            }
        }
    } finally {
        Hooks.onError = previousOnError
        Dispatchers.resetMain()
    }
}
