package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** rememberReducer, rememberMemo, rememberRef, rememberUpdated, rememberFlow, rememberId. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactTest {
    private lateinit var host: Host

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        host = Host()
        Hooks.onError = { throw it }
    }

    @After fun tearDown() {
        host.close()
        Dispatchers.resetMain()
        Hooks.onError = { throw it }
    }

    // ---------------------------------------------------------------- rememberReducer
    @Test fun reducerDispatchStable() {
        val owner = FakeOwner()
        val dispatches = ArrayList<(Int) -> Unit>()
        var state = 0
        host.show(owner) {
            val (s, dispatch) = rememberReducer<Int, Int>({ acc, a -> acc + a }) { 10 }
            state = s
            dispatches += dispatch
        }
        host.act { dispatches.last()(5) }
        host.act { dispatches.last()(1) }
        assertEquals(16, state)
        assertEquals(1, dispatches.toSet().size)
    }

    @Test fun reducerInitOnce_latestReducerUsed_equalResultSkipsRecomposition() {
        val owner = FakeOwner()
        val factor = mutableIntStateOf(1)
        var inits = 0
        var compositions = 0
        var reducer: ReducerState<Int, Int>? = null
        host.show(owner) {
            val f = factor.intValue
            compositions++
            val r = rememberReducer<Int, Int>({ acc, a -> acc + a * f }) { inits++; 0 }
            r.value
            reducer = r
        }
        reducer!!.dispatch(1)
        host.frame()
        assertEquals(1, reducer!!.value)
        host.act { factor.intValue = 10 }
        reducer!!.dispatch(1)
        host.frame()
        assertEquals("the reducer from the latest pass is used", 11, reducer!!.value)
        val c = compositions
        reducer!!.dispatch(0)
        host.frame()
        assertEquals("same state -> no recomposition", c, compositions)
        assertEquals(1, inits)
        val (v, d) = reducer!!
        assertEquals(11, v)
        assertSame(reducer!!.dispatch, d)
    }

    @Test fun reducerDefaultReducerKeepsState() {
        val r = ReducerState<Int, String>(3)
        r.dispatch("ignored")
        assertEquals(3, r.value)
    }

    // ---------------------------------------------------------------- rememberMemo / rememberRef / rememberUpdated / rememberId
    @Test fun memoRefUpdatedId() {
        val owner = FakeOwner()
        val dep = mutableIntStateOf(1)
        val other = mutableIntStateOf(0)
        var computes = 0
        var memo = 0
        var ref: Ref<Int>? = null
        var updated: State<Int>? = null
        val ids = ArrayList<String>()
        var compositions = 0
        val content: @Composable () -> Unit = {
            compositions++
            other.intValue
            memo = rememberMemo(dep.intValue) { computes++; dep.intValue * 10 }
            ref = rememberRef { 0 }
            updated = rememberUpdated(other.intValue)
            ids += rememberId()
            ids += rememberId()
        }
        host.show(owner, content)
        host.act { other.intValue = 1 }
        assertEquals(1, computes)
        host.act { dep.intValue = 2 }
        assertEquals(2, computes); assertEquals(20, memo)
        val c = compositions
        ref!!.current = 99; host.frame()
        assertEquals("ref write does not recompose", c, compositions)
        assertEquals(1, updated!!.value)
        host.show(owner, content)
        assertEquals(2, ids.toSet().size)
        assertEquals("ref survives reshow", 99, ref!!.current)
    }

    @Test fun memoMultipleDeps_noDepsComputedOnce_insideCustomRemember() {
        val owner = FakeOwner()
        val a = mutableIntStateOf(1)
        val b = mutableStateOf("x")
        val tick = mutableIntStateOf(0)
        var computes = 0
        var noDeps = 0
        val values = ArrayList<String>()
        host.show(owner) {
            tick.intValue
            val av = a.intValue; val bv = b.value
            customRemember {
                values += rememberMemo(av, bv) { computes++; "$bv$av" }
                rememberMemo { noDeps++ }
            }
        }
        host.act { tick.intValue++ }
        host.act { a.intValue = 2 }
        host.act { b.value = "y" }
        host.act { b.value = "y" }
        assertEquals(listOf("x1", "x1", "x2", "y2"), values)
        assertEquals(3, computes)
        assertEquals(1, noDeps)
    }

    @Test fun updatedAlwaysHoldsLatestValueForLongLivedWork() {
        val owner = FakeOwner()
        val label = mutableStateOf("a")
        var callback: (() -> String)? = null
        host.show(owner) {
            val latest = rememberUpdated(label.value)
            rememberEffect { callback = { latest.value } } // effect captured once, never restarted
        }
        host.act { label.value = "b" }
        host.act { label.value = "c" }
        assertEquals("c", callback!!())
    }

    @Test fun idHasPrefix_uniquePerInstance_stablePerInstance() {
        val o1 = FakeOwner(); val o2 = FakeOwner()
        var id: String? = null
        val tick = mutableIntStateOf(0)
        val content: @Composable () -> Unit = { tick.intValue; id = rememberId("row-") }
        host.show(o1, content)
        val first = id!!
        host.act { tick.intValue++ }
        assertEquals(first, id)
        assertTrue(first.startsWith("row-"))
        host.show(o2, content)
        assertNotEquals(first, id)
        var defaultId: String? = null
        host.show(FakeOwner()) { defaultId = rememberId() }
        assertTrue(defaultId!!.startsWith("h"))
    }

    // ---------------------------------------------------------------- rememberFlow
    @Test fun flowCollectsUntilPop() {
        val owner = FakeOwner()
        val flow = MutableStateFlow(1)
        var state: State<Int>? = null
        host.show(owner) { state = rememberFlow(flow, 0) }
        assertEquals(1, state!!.value)
        flow.value = 2; host.frame()
        assertEquals(2, state!!.value)
        host.hide()
        flow.value = 3
        assertEquals("still collecting while hidden (owner alive)", 3, state!!.value)
        owner.pop()
        flow.value = 4
        assertEquals(3, state!!.value)
        assertEquals(0, flow.subscriptionCount.value)
    }

    @Test fun flowInitialShownUntilFirstEmission() {
        val owner = FakeOwner()
        val rendered = ArrayList<String>()
        val mapped = MutableStateFlow(0).map { "v$it" }
        host.show(owner) { rendered += rememberFlow(mapped, "initial").value }
        assertEquals("initial", rendered.first())
        assertEquals("v0", rendered.last())
    }

    @Test fun flowRestartsWhenKeysOrFlowChange() {
        val owner = FakeOwner()
        val key = mutableIntStateOf(1)
        val source = mutableStateOf(MutableStateFlow("a"))
        val starts = ArrayList<String>()
        var state: State<String>? = null
        host.show(owner) {
            val k = key.intValue
            val src = source.value
            state = rememberFlow(flow { starts += "start:$k"; src.collect { emit("$it$k") } }.let { f -> remember1(src, k, f) }, "-", k)
        }
        assertEquals("a1", state!!.value)
        host.act { key.intValue = 2 }
        assertEquals("a2", state!!.value)
        val old = source.value
        host.act { source.value = MutableStateFlow("b") }
        assertEquals("b2", state!!.value)
        assertEquals(0, old.subscriptionCount.value)
        assertEquals(listOf("start:1", "start:2", "start:2"), starts)
    }

    @Test fun failingCollectorGoesToHooksOnError() {
        val owner = FakeOwner()
        val errors = ArrayList<Throwable>()
        Hooks.onError = { errors += it }
        host.show(owner) { rememberFlow(flow<Int> { throw IllegalStateException("flow-fail") }, 0) }
        assertEquals(listOf("flow-fail"), errors.map { it.message })
    }
}

/** Memoizes [value] per (a, b) so the flow instance is stable unless a dep changes (test helper, not library code). */
@Composable
private fun <T> remember1(a: Any?, b: Any?, value: T): T = androidx.compose.runtime.remember(a, b) { value }
