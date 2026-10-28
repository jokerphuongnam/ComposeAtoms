package compose.hooks.test

import androidx.compose.runtime.mutableIntStateOf
import compose.hooks.Hooks
import compose.hooks.customRemember
import compose.hooks.rememberContext
import compose.hooks.rememberContextOf
import compose.hooks.Provider
import compose.hooks.rememberEffect
import compose.hooks.rememberRef
import compose.hooks.rememberState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val Theme = rememberContextOf<String>("Theme")
private val Locale = rememberContextOf<String>("Locale") { "en" }

/** hooks-test itself: rendering, history, lifecycle, contexts, wrappers, errors, strict history, time, teardown. */
class HookTestTest {
    private inline fun <reified E : Throwable> assertFails(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw e
        }
        fail("expected ${E::class.java.simpleName}")
        throw IllegalStateException()
    }

    @Test fun renderHook_current_history_recomposeCount_act() = runHookTest {
        val result = renderHook { val s = rememberState { 0 }; s.value to s }
        assertEquals(0, result.current.first)
        assertEquals(1, result.recomposeCount)
        result.act { result.current.second.value = 1 }
        result.act { result.current.second.value = 2 }
        assertEquals(listOf(0, 1, 2), result.history.map { it.first })
        assertEquals(3, result.recomposeCount)
        assertTrue(result.isShown)
        assertFalse(result.wasCleared)
        assertTrue(result.owner != null)
    }

    @Test fun currentBeforeAnyCommitFails() = runHookTest {
        val never = HookResult<Unit, Int>(this as HookTestHost, null, Unit, emptyList(), emptyList(), false) { 1 }
        assertTrue(assertFails<AssertionError> { never.current }.message!!.contains("not committed"))
    }

    @Test fun rerenderPassesNewProps() = runHookTest {
        val result = renderHookWithProps("a") { p -> customRemember { rememberRef { p }.current to p } }
        result.rerender("b")
        assertEquals("a" to "b", result.current)
        assertEquals(listOf("a" to "a", "a" to "b"), result.history)
    }

    @Test fun hideShowRecreateKeepOwnerState() = runHookTest {
        var inits = 0
        val result = renderHook { rememberState { ++inits } }
        val state = result.current
        result.hide()
        assertFalse(result.isShown)
        assertFalse("hidden is not cleared", result.wasCleared)
        assertTrue(assertFails<IllegalStateException> { result.hide() }.message!!.contains("hidden"))
        result.show()
        assertTrue(assertFails<IllegalStateException> { result.show() }.message!!.contains("shown"))
        result.recreate()
        assertSame(state, result.current)
        assertEquals(1, inits)
    }

    @Test fun popClearsHideFirstIdempotent() = runHookTest {
        val entry = backStackEntry()
        var cleaned = 0
        val result = renderHook(entry) { rememberEffect(Unit) { { cleaned++ } } }
        assertEquals(1, entry.hookScopeCount)
        assertFalse(entry.isPopped)
        entry.pop()
        assertTrue(entry.isPopped)
        assertFalse("pop hides first", result.isShown)
        assertTrue(result.wasCleared)
        assertEquals(1, cleaned)
        assertEquals("nothing leaked", 0, entry.hookScopeCount)
        entry.pop()
        assertEquals(1, cleaned)
        assertTrue(assertFails<IllegalStateException> { result.show() }.message!!.contains("popped"))
    }

    @Test fun twoHooksOnOneEntryAreClearedTogether() = runHookTest {
        val entry = backStackEntry()
        val a = renderHook(entry) { rememberState { "a" } }
        val b = renderHook(entry) { rememberState { "b" } }
        entry.pop()
        assertTrue(a.wasCleared && b.wasCleared)
    }

    @Test fun noOwnerIsThePreviewPath_stateIsPerComposition() = runHookTest {
        var inits = 0
        val result = renderHook(owner = null) { rememberState { ++inits } }
        assertEquals(null, result.owner)
        val first = result.current
        result.recreate()
        assertNotSame("no owner: state lives with the composition", first, result.current)
        assertEquals(2, inits)
        result.hide()
        assertTrue(result.wasCleared)
    }

    @Test fun provideAndWrappers() = runHookTest {
        val order = ArrayList<String>()
        addWrapper { content -> order += "outer"; content() }
        val viaProvides = renderHook(provide = listOf(Theme provides "dark")) { rememberContext(Theme) + rememberContext(Locale) }
        assertEquals("darken", viaProvides.current)
        val viaProvide = renderHook(provide = listOf(provide(Theme, "light"), Locale provides "vi")) {
            rememberContext(Theme) + "/" + rememberContext(Locale)
        }
        assertEquals("light/vi", viaProvide.current)
        val wrapped = renderHook(wrapper = { content -> order += "inner"; Theme.Provider("wrapped") { content() } }) {
            rememberContext(Theme)
        }
        assertEquals("wrapped", wrapped.current)
        assertEquals(listOf("outer", "outer", "outer", "inner"), order)
    }

    @Test fun errorsAreCollected_takeErrorsKeepsTheTestGreen() = runHookTest {
        renderHook { rememberEffect(Unit) { throw IllegalStateException("effect failed") } }
        val launched = renderHook { rememberEffect(Unit) { launch { delay(10); error("coroutine failed") } } }
        advanceTimeBy(10)
        assertEquals(listOf("effect failed", "coroutine failed"), takeErrors().map { it.message })
        assertTrue(takeErrors().isEmpty())
        assertTrue(launched.isShown)
    }

    @Test fun untakenErrorsFailTheTest_assertionErrorsAsIs_othersWrapped() {
        val wrapped = assertFails<AssertionError> {
            runHookTest {
                renderHook { rememberEffect(Unit) { throw IllegalStateException("one") } }
                renderHook { rememberEffect(Unit) { throw IllegalArgumentException("two") } }
            }
        }
        assertTrue(wrapped.message!!.contains("2 error(s)"))
        assertEquals("one", wrapped.cause!!.message)
        assertEquals("two", wrapped.suppressed.single().message)
        val direct = assertFails<AssertionError> {
            runHookTest { renderHook { rememberEffect(Unit) { throw AssertionError("inside") } } }
        }
        assertEquals("inside", direct.message)
    }

    @Test fun hooksOnErrorAndMainAreRestored() {
        val before = Hooks.onError
        runHookTest { assertNotSame(before, Hooks.onError) }
        assertSame(before, Hooks.onError)
        assertTrue(assertFails<IllegalStateException> { Dispatchers.Main.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext) }
            .message!!.contains("Main"))
    }

    @Test fun strictHistory_nextValue_expectNoValue_skip_ignore() = runHookTest {
        val tick = mutableIntStateOf(0)
        val result = renderHook(strictHistory = true) { tick.intValue }
        assertEquals(0, result.nextValue())
        result.expectNoValue()
        assertTrue(assertFails<AssertionError> { result.nextValue() }.message!!.contains("none left"))
        result.act { tick.intValue = 1 }
        result.act { tick.intValue = 2 }
        assertEquals(listOf(1, 2), result.unconsumed)
        assertTrue(assertFails<AssertionError> { result.expectNoValue() }.message!!.contains("[1, 2]"))
        result.skipValues(1)
        assertEquals(2, result.nextValue())
        result.act { tick.intValue = 3 }
        result.ignoreRemaining()
        result.expectNoValue()
    }

    @Test fun strictHistoryFailsTheTestWithUnconsumedValues() {
        val e = assertFails<AssertionError> {
            runHookTest { renderHook(strictHistory = true) { 42 } }
        }
        assertTrue(e.message!!.contains("unconsumed hook values [42]"))
    }

    @Test fun virtualTime() = runHookTest {
        val result = renderHook {
            val s = rememberState { "start" }
            rememberEffect(Unit) { launch { delay(1_000); s.value = "done" } }
            s.value
        }
        assertEquals(0L, currentTime)
        advanceTimeBy(999)
        assertEquals("start", result.current)
        advanceTimeBy(1)
        assertEquals("done", result.current)
        assertEquals(1_000L, currentTime)
        assertSame(testScope.testScheduler, (mainDispatcher.scheduler))
        val later = renderHook {
            val s = rememberState { 0 }
            rememberEffect(Unit) { launch { delay(5.seconds); s.value = 1 } }
            s.value
        }
        advanceTimeBy(2.seconds)
        assertEquals(0, later.current)
        advanceUntilIdle()
        assertEquals(1, later.current)
        runCurrent()
    }

    @Test fun teardownPopsLiveEntries_stopsEndlessLoops_runsCallbacksInReverse() {
        lateinit var entry: BackStackEntry
        val order = ArrayList<String>()
        var loops = 0
        runHookTest(timeout = 5.seconds) {
            entry = backStackEntry()
            renderHook(entry) { rememberEffect(Unit) { launch { while (true) { delay(100); loops++ } } } }
            advanceTimeBy(1_000)
            onTeardown { order += "first" }
            onTeardown { order += "second:" + entry.isPopped }
        }
        assertTrue(entry.isPopped)
        assertEquals(0, entry.hookScopeCount)
        assertEquals(listOf("second:true", "first"), order)
        assertTrue(loops >= 10)
    }

    @Test fun aFailingTeardownIsReported() {
        val e = assertFails<AssertionError> { runHookTest { onTeardown { error("teardown failed") } } }
        assertEquals("teardown failed", e.cause!!.message)
    }
}
