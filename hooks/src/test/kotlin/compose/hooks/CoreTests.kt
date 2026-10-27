package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Box(val name: String)

@OptIn(ExperimentalCoroutinesApi::class)
class CoreTests {
    private lateinit var host: Host
    private val log = ArrayList<String>()

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

    // ---------------------------------------------------------------- rememberState
    @Test fun stateSurvivesRecompositionReshowAndRecreate_initOnce() {
        val owner = FakeOwner()
        var inits = 0
        var seen: MutableState<Int>? = null
        val content: @Composable () -> Unit = { seen = rememberState { inits++; 1 } }
        host.show(owner, content)
        val first = seen!!
        host.act { first.value = 5 }
        host.show(owner, content) // reshow / rotation: new composition, same owner
        assertSame(first, seen)
        assertEquals(5, seen!!.value)
        assertEquals(1, inits)
        owner.pop()
        val other = FakeOwner()
        host.show(other, content)
        assertNotSame(first, seen)
        assertEquals(2, inits)
    }

    @Test fun valueFormStoresFirstValue_argumentStillEvaluatedEachPass() {
        val owner = FakeOwner()
        var evaluated = 0
        val trigger = mutableIntStateOf(0)
        var state: MutableState<String>? = null
        fun arg(): String { evaluated++; return "v$evaluated" }
        host.show(owner) {
            trigger.intValue
            state = rememberState(arg())
        }
        host.act { trigger.intValue++ }
        host.act { trigger.intValue++ }
        assertEquals("v1", state!!.value)
        assertEquals(3, evaluated) // documented: without the compiler plugin the argument runs every pass
    }

    @Test fun skipOnEqualAndBatching() {
        val owner = FakeOwner()
        var compositions = 0
        var state: MutableState<Int>? = null
        host.show(owner) {
            val s = rememberState { 0 }
            state = s
            s.value
            compositions++
        }
        assertEquals(1, compositions)
        host.act { state!!.value = 0 }
        assertEquals("equal write must not recompose", 1, compositions)
        host.act { state!!.value = 1; state!!.update { it + 1 }; state!!.update { it + 1 } }
        assertEquals("three writes, one recomposition", 2, compositions)
        assertEquals(3, state!!.value)
        val (value, set) = state!!
        assertEquals(3, value)
        host.act { set(4) }
        assertEquals(4, state!!.value)
    }

    @Test fun slotOrderViolationThrows() {
        val owner = FakeOwner()
        val flag = mutableStateOf(true)
        host.show(owner) {
            customRemember {
                rememberState { 1 }
                if (flag.value) rememberState { 2 }
            }
        }
        var error: Throwable? = null
        try { host.act { flag.value = false } } catch (t: Throwable) { error = t }
        if (error == null) error = host.errors.firstOrNull()
        assertTrue("got $error", error is IllegalStateException && error.message!!.contains("same order"))
    }

    // ---------------------------------------------------------------- customRemember scope tree
    class Inner(val a: MutableState<Int>, val scope: RememberScope)
    class Outer(val inner1: Inner, val inner2: Inner, val own: MutableState<String>, val scope: RememberScope)

    @Composable fun rememberInner(tag: String): Inner = customRemember {
        val a = rememberState { 0 }
        rememberEffect { log += "run:$tag"; { log += "clean:$tag" } }
        Inner(a, ScopeStack.peek()!!)
    }

    @Composable fun rememberOuter(): Outer = customRemember {
        val own = rememberState { "x" }
        val i1 = rememberInner("i1")
        val i2 = rememberInner("i2")
        rememberEffect { log += "run:outer"; { log += "clean:outer" } }
        Outer(i1, i2, own, ScopeStack.peek()!!)
    }

    @Test fun nestingSameHookTwiceDistinct_popClearsChildrenFirst() {
        val owner = FakeOwner()
        var outer: Outer? = null
        val content: @Composable () -> Unit = { outer = rememberOuter() }
        host.show(owner, content)
        val o = outer!!
        assertNotSame(o.inner1, o.inner2)
        assertNotSame(o.inner1.a, o.inner2.a)
        assertEquals(2, o.scope.debugChildCount())
        assertEquals(listOf("run:i1", "run:i2", "run:outer"), log)
        host.show(owner, content)
        assertSame(o.inner1.a, outer!!.inner1.a)
        assertEquals("no rerun on reshow", 3, log.size)
        host.hide()
        log.clear()
        owner.pop()
        assertEquals(listOf("clean:i2", "clean:i1", "clean:outer"), log)
        assertTrue(o.scope.isCleared && o.inner1.scope.isCleared && o.inner2.scope.isCleared)
        assertEquals(0, o.scope.debugChildCount())
        assertEquals(0, o.scope.debugSlotCount())
    }

    @Test fun gcAfterPop() {
        val owner = FakeOwner()
        var ref: WeakReference<Any>? = null
        host.show(owner) {
            val s = rememberState { Box("leak-check") }
            if (ref == null) ref = WeakReference(s.value)
        }
        host.hide()
        owner.pop()
        assertTrue(gcUntil { ref!!.get() == null })
    }

    @Test fun conditionalChildResetsWhenRemoved_keyChangeResets() {
        val owner = FakeOwner()
        val show = mutableStateOf(true)
        val k = mutableIntStateOf(1)
        var cond: Inner? = null
        var keyed: Inner? = null
        host.show(owner) {
            customRemember {
                if (show.value) cond = rememberInner("cond")
                key(k.intValue) { keyed = rememberInner("k${k.intValue}") }
            }
        }
        val c1 = cond!!
        val k1 = keyed!!
        host.act { show.value = false }
        assertTrue(c1.scope.isCleared)
        assertTrue(log.contains("clean:cond"))
        host.act { show.value = true }
        assertNotSame(c1, cond)
        host.act { k.intValue = 2 }
        assertTrue(k1.scope.isCleared)
        assertNotSame(k1, keyed)
        assertTrue(log.contains("clean:k1") && log.contains("run:k2"))
    }

    @Test fun loopWithoutKeyIsPositional_loopWithKeyFollowsKey() {
        val owner = FakeOwner()
        val ids = mutableStateOf(listOf(1, 2, 3))
        val plain = HashMap<Int, Inner>()
        val keyed = HashMap<Int, Inner>()
        host.show(owner) {
            customRemember {
                for (id in ids.value) plain[id] = rememberInner("p$id")
                for (id in ids.value) key(id) { keyed[id] = rememberInner("k$id") }
            }
        }
        assertEquals(3, plain.values.map { it.a }.toSet().size)
        val p1 = plain[1]!!.a; val k1 = keyed[1]!!; val k3 = keyed[3]!!.a
        host.act { ids.value = listOf(3, 2, 1) }
        assertSame(k1.a, keyed[1]!!.a); assertSame(k3, keyed[3]!!.a)
        assertSame("positional: id 3 now gets position-0 holder", p1, plain[3]!!.a)
        host.act { ids.value = listOf(3, 2) }
        assertTrue(k1.scope.isCleared)
    }

    // EDGE: a hook used inside a restartable child composable that recomposes on its own
    @Composable fun RestartableChild(read: MutableState<Int>, out: (MutableState<Int>) -> Unit) {
        read.value
        out(rememberState { 0 })
    }

    @Test fun edgeRestartableChildKeepsStateWhenRecomposingAlone() {
        val owner = FakeOwner()
        val childTick = mutableStateOf(0)
        val parentTick = mutableIntStateOf(0)
        var parentComposes = 0
        val seen = ArrayList<MutableState<Int>>()
        host.show(owner) {
            customRemember {
                parentTick.intValue
                parentComposes++
                rememberState { "parent" }
                RestartableChild(childTick) { seen += it }
            }
        }
        seen.last().value = 7
        host.act { childTick.value = 1 }
        assertEquals("child recomposed alone", 1, parentComposes)
        assertSame(seen.first(), seen.last())
        host.act { parentTick.intValue = 1 }
        assertEquals(2, parentComposes)
        assertSame(seen.first(), seen.last())
        assertEquals(7, seen.last().value)
    }

    // ---------------------------------------------------------------- rememberEffect (option 5)
    @Test fun effectCleanupSemantics() {
        val owner = FakeOwner()
        val dep = mutableIntStateOf(1)
        var jobCancelledAtCleanup: Boolean? = null
        val content: @Composable () -> Unit = {
            val d = dep.intValue
            rememberEffect(d) {
                log += "run:$d"
                val j = launch { awaitCancellation() }
                val cleanup = { jobCancelledAtCleanup = j.isCancelled; log += "clean:$d" }
                cleanup
            }
        }
        host.show(owner, content)
        assertEquals(listOf("run:1"), log)
        host.hide(); host.show(owner, content)
        assertEquals("not rerun / cleaned on reshow", listOf("run:1"), log)
        host.act { dep.intValue = 2 }
        assertEquals(listOf("run:1", "clean:1", "run:2"), log)
        assertEquals("job cancelled before cleanup", true, jobCancelledAtCleanup)
        host.act { dep.intValue = 3 }
        host.act { dep.intValue = 3 }
        assertEquals(listOf("run:1", "clean:1", "run:2", "clean:2", "run:3"), log)
        host.hide()
        owner.pop()
        assertEquals("clean:3", log.last())
    }

    @Test fun effectNonFunctionResultIsNotCleanup_reverseDeclarationOrder() {
        val owner = FakeOwner()
        host.show(owner) {
            customRemember {
                rememberEffect { log += "a"; { log += "clean:a" } }
                rememberEffect { log += "b"; 42 }
                rememberEffect { log += "c"; { log += "clean:c" } }
            }
        }
        owner.pop()
        assertEquals(listOf("a", "b", "c", "clean:c", "clean:a"), log)
    }

    @Test fun throwingCleanupDoesNotStopOthers_errorReportedOnce() {
        val owner = FakeOwner()
        val errors = ArrayList<Throwable>()
        Hooks.onError = { errors += it }
        host.show(owner) {
            customRemember {
                rememberEffect { { log += "clean:1" } }
                rememberEffect { { throw IllegalStateException("boom") } }
                rememberEffect { { log += "clean:3" } }
            }
        }
        owner.pop()
        assertEquals(listOf("clean:3", "clean:1"), log)
        assertEquals(1, errors.size)
        assertEquals("boom", errors[0].message)
    }

    @Test fun effectRunsOnlyAfterCommit_abandonedCompositionDoesNotRun() {
        val owner = FakeOwner()
        try {
            host.show(owner) {
                rememberEffect { log += "run" }
                throw RuntimeException("abandon")
            }
        } catch (_: RuntimeException) {}
        assertEquals(emptyList<String>(), log)
    }

    // ---------------------------------------------------------------- context lending L1-L4
    private val Ctx = hookContextOf<Box>("Box")

    @Test fun contextL1toL4() {
        val provOwner = FakeOwner()
        val consOwner = FakeOwner()
        val value = mutableStateOf(Box("a"))
        var seen: Box? = null
        var inner: Box? = null
        var consumerScope: RememberScope? = null
        host.show(provOwner) {
            Ctx.Provider(value.value) {
                customRemember { seen = rememberContext(Ctx); consumerScope = ScopeStack.peek() }
                Ctx.Provider(Box("inner")) { inner = rememberContext(Ctx) }
            }
        }
        assertEquals("L1 consumer sees provider", "a", seen!!.name)
        assertEquals("L4 nearest provider wins", "inner", inner!!.name)
        host.act { value.value = Box("b") }
        assertEquals("L1 update propagates", "b", seen!!.name)
        // L2: consumer drops its lease when its own owner clears
        val ref = consumerScope!!.slots[0] as ConsumerRef<*>
        assertTrue(ref.lease != null)
        provOwner.pop()
        assertEquals(null, ref.lease)
        // L3: reading a lease whose provider owner was cleared fails fast
        var lease: Lease<Box>? = null
        host.show(consOwner) {
            Ctx.Provider(Box("p")) { lease = Ctx.local.current }
        }
        host.hide()
        consOwner.pop()
        var err: Throwable? = null
        try { lease!!.value } catch (t: Throwable) { err = t }
        assertTrue(err is RevokedContextException)
    }

    @Test fun missingProviderThrows_defaultUsed() {
        val owner = FakeOwner()
        var err: Throwable? = null
        try { host.show(owner) { rememberContext(Ctx) } } catch (t: Throwable) { err = t }
        assertTrue(err is MissingProviderException)
        val withDefault = hookContextOf("D") { Box("default") }
        var got: Box? = null
        host.show(FakeOwner()) { got = rememberContext(withDefault) }
        assertEquals("default", got!!.name)
    }

    // ---------------------------------------------------------------- React parity hooks
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

    @Test fun memoRefUpdatedId() {
        val owner = FakeOwner()
        val dep = mutableIntStateOf(1)
        val other = mutableIntStateOf(0)
        var computes = 0
        var memo = 0
        var ref: Ref<Int>? = null
        var updated: androidx.compose.runtime.State<Int>? = null
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
    }

    @Test fun flowCollectsUntilPop() {
        val owner = FakeOwner()
        val flow = kotlinx.coroutines.flow.MutableStateFlow(1)
        var state: androidx.compose.runtime.State<Int>? = null
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

    // ---------------------------------------------------------------- C2: callbacks capturing hook setters stay stable
    @Composable fun Child(onClick: () -> Unit, count: IntArray) { count[0]++; onClick.hashCode() }

    @Test fun c2CallbackCapturingStateIsStable() {
        val owner = FakeOwner()
        val tick = mutableIntStateOf(0)
        val childCount = IntArray(1)
        host.show(owner) {
            tick.intValue
            val s = rememberState { 0 }
            var v by rememberState { 0 }
            Child(onClick = { s.value++; v++ }, count = childCount)
        }
        host.act { tick.intValue++ }
        host.act { tick.intValue++ }
        assertEquals("child skipped: lambda capturing hook state is memoized (strong skipping)", 1, childCount[0])
    }

    /** Data point: MutableState.component2() returns a new lambda each call, so a callback capturing it is not memoized. */
    @Test fun c2CallbackCapturingDestructuredSetterIsNotStable() {
        val owner = FakeOwner()
        val tick = mutableIntStateOf(0)
        val childCount = IntArray(1)
        host.show(owner) {
            tick.intValue
            val (_, set) = rememberState { 0 }
            Child(onClick = { set(1) }, count = childCount)
        }
        host.act { tick.intValue++ }
        host.act { tick.intValue++ }
        assertEquals(3, childCount[0])
    }
}
