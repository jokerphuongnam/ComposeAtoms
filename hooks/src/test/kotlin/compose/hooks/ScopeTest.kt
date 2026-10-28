package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class Box(val name: String)

/** customRemember, RememberScope (scope tree, clear order, coroutine scope), Hooks, HooksViewModel, top scopes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScopeTest {
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

    private fun expectFailure(block: () -> Unit): Throwable {
        val before = host.errors.size
        try { block() } catch (t: Throwable) { return t }
        return host.errors.getOrNull(before) ?: run { fail("expected a failure"); throw AssertionError() }
    }

    private object NoopRecomposeScope : RecomposeScope { override fun invalidate() {} }

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

    // ---------------------------------------------------------------- customRemember basics
    @Test fun blockRunsEveryPassAndReturnsLatestValue_stateKeptAcrossRecomposition() {
        val owner = FakeOwner()
        val tick = mutableIntStateOf(0)
        var passes = 0
        val results = ArrayList<Pair<Int, MutableState<Int>>>()
        host.show(owner) {
            val t = tick.intValue
            results += customRemember { passes++; t to rememberState { 0 } }
        }
        host.act { tick.intValue = 1 }
        host.act { tick.intValue = 2 }
        assertEquals(3, passes)
        assertEquals(listOf(0, 1, 2), results.map { it.first })
        assertEquals(1, results.map { it.second }.toSet().size)
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
        assertSame(o.own, outer!!.own)
        assertEquals("no rerun on reshow", 3, log.size)
        host.hide()
        log.clear()
        owner.pop()
        assertEquals(listOf("clean:i2", "clean:i1", "clean:outer"), log)
        assertTrue(o.scope.isCleared && o.inner1.scope.isCleared && o.inner2.scope.isCleared)
        assertEquals(0, o.scope.debugChildCount())
        assertEquals(0, o.scope.debugSlotCount())
    }

    @Test fun threeLevelNestingKeepsEveryLevel() {
        val owner = FakeOwner()
        val tick = mutableIntStateOf(0)
        val seen = ArrayList<Triple<MutableState<Int>, MutableState<Int>, MutableState<Int>>>()
        host.show(owner) {
            tick.intValue
            seen += customRemember {
                val a = rememberState { 1 }
                val outerScope = ScopeStack.peek()
                val (b, c) = customRemember {
                    assertSame("child pass records its parent", outerScope, ScopeStack.peek()!!.passParent)
                    val b = rememberState { 2 }
                    b to customRemember { rememberState { 3 } }
                }
                Triple(a, b, c)
            }
        }
        seen.last().third.value = 30
        host.act { tick.intValue++ }
        assertEquals(2, seen.size)
        assertSame(seen[0].first, seen[1].first)
        assertSame(seen[0].second, seen[1].second)
        assertSame(seen[0].third, seen[1].third)
        assertEquals(listOf(1, 2, 30), seen.last().toList().map { it.value })
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

    // ---------------------------------------------------------------- explicit keys and call-site identity
    @Test fun explicitKeyOnTopLevel_differentKeyDifferentScope_sameKeyComesBack() {
        val owner = FakeOwner()
        val k = mutableStateOf("a")
        val seen = ArrayList<MutableState<Int>>()
        host.show(owner) { seen += customRemember(key = k.value) { rememberState { 0 } } }
        val a = seen.last()
        a.value = 5
        host.act { k.value = "b" }
        assertNotSame(a, seen.last())
        assertEquals(0, seen.last().value)
        host.act { k.value = "a" }
        assertSame("top scopes live with the owner, so key a's state is still there", a, seen.last())
        assertEquals(5, seen.last().value)
        assertEquals(2, debugTopScopeCount(owner))
    }

    @Test fun explicitKeyOnChild_keyChangeClearsOldChild() {
        val owner = FakeOwner()
        val k = mutableIntStateOf(1)
        val scopes = ArrayList<RememberScope>()
        host.show(owner) {
            customRemember {
                customRemember(key = k.intValue) {
                    rememberEffect { val my = k.intValue; log += "run:$my"; { log += "clean:$my" } }
                    scopes += ScopeStack.peek()!!
                }
            }
        }
        val first = scopes.last()
        host.act { k.intValue = 2 }
        assertTrue(first.isCleared)
        assertNotSame(first, scopes.last())
        assertEquals(listOf("run:1", "clean:1", "run:2"), log)
    }

    @Test fun sameCallSiteManyTimes_occurrenceIdsAvoidCollisions() {
        // key hashes 32 and 1: the second occurrence of key 1 would map to 1*31+1 = 32, which is taken -> probe on
        class K(val h: Int) { override fun hashCode() = h; override fun equals(other: Any?) = other is K && other.h == h }
        val owner = FakeOwner()
        val tick = mutableIntStateOf(0)
        val seen = ArrayList<List<RememberScope>>()
        var parent: RememberScope? = null
        host.show(owner) {
            tick.intValue
            customRemember {
                parent = ScopeStack.peek()
                val out = ArrayList<RememberScope>()
                out += customRemember(key = K(32)) { ScopeStack.peek()!! }
                out += customRemember(key = K(1)) { ScopeStack.peek()!! }
                out += customRemember(key = K(1)) { ScopeStack.peek()!! }
                out += customRemember(key = K(1)) { ScopeStack.peek()!! }
                seen += out
            }
        }
        assertEquals(4, seen[0].toSet().size)
        assertEquals(4, parent!!.debugChildCount())
        host.act { tick.intValue++ }
        assertEquals("identities are stable across passes", seen[0], seen[1])
    }

    @Test fun bareHooksGetOwnImplicitScopesPerCallSite() {
        val owner = FakeOwner()
        val tick = mutableIntStateOf(0)
        val a = ArrayList<MutableState<Int>>()
        val b = ArrayList<MutableState<Int>>()
        var activeInside: RememberScope? = RememberScope("sentinel")
        host.show(owner) {
            tick.intValue
            a += rememberState { 1 }
            b += rememberState { 2 }
            activeInside = ScopeStack.peek()
        }
        host.act { tick.intValue++ }
        assertNotSame(a[0], b[0])
        assertSame(a[0], a[1]); assertSame(b[0], b[1])
        assertNull("no scope stays active after a bare hook returns", activeInside)
        assertEquals(2, debugTopScopeCount(owner))
    }

    @Test fun scopeStackRestoredWhenBlockThrows() {
        val owner = FakeOwner()
        var inside: RememberScope? = null
        val e = runCatching {
            host.show(owner) {
                customRemember<Unit> { inside = ScopeStack.peek(); throw IllegalArgumentException("in block") }
            }
        }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(inside != null)
        assertNull("composition runs on this thread; the stack was popped in finally", ScopeStack.peek())
    }

    // ---------------------------------------------------------------- rules of hooks at runtime
    @Test fun fewerSlotHooksThanFirstPassThrows() {
        val owner = FakeOwner()
        val flag = mutableStateOf(true)
        host.show(owner) {
            customRemember {
                rememberState { 1 }
                if (flag.value) rememberState { 2 }
            }
        }
        val error = expectFailure { host.act { flag.value = false } }
        assertTrue("got $error", error is IllegalStateException && error.message!!.contains("same order"))
    }

    @Test fun moreSlotHooksThanFirstPassThrows() {
        val owner = FakeOwner()
        val flag = mutableStateOf(false)
        host.show(owner) {
            customRemember {
                rememberState { 1 }
                if (flag.value) rememberState { 2 }
            }
        }
        val error = expectFailure { host.act { flag.value = true } }
        assertTrue("got $error", error is IllegalStateException && error.message!!.contains("more slot hooks"))
    }

    @Test fun composingAClearedScopeThrows() {
        val scope = RememberScope("dead")
        scope.clear()
        val e = runCatching { scope.beginPass(NoopRecomposeScope, null) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("cleared"))
    }

    // ---------------------------------------------------------------- RememberScope clear / onClear / coroutineScope
    @Test fun clearOrder_childrenEffectsCoroutinesThenReleases_idempotent() {
        val owner = FakeOwner()
        var scope: RememberScope? = null
        var job: Job? = null
        host.show(owner) {
            customRemember {
                val s = ScopeStack.peek()!!
                scope = s
                rememberMemo { s.onClear { log += "release:1:jobCancelled=${job!!.isCancelled}" }; s.onClear { log += "release:2" } }
                rememberMemo { job = s.coroutineScope.launch { awaitCancellation() } }
                rememberEffect { { log += "effect" } }
                customRemember { rememberEffect { { log += "child" } } }
            }
        }
        val s = scope!!
        assertSame("coroutineScope is created once", s.coroutineScope, s.coroutineScope)
        assertFalse(s.isCleared)
        owner.pop()
        assertEquals(listOf("child", "effect", "release:2", "release:1:jobCancelled=true"), log)
        assertTrue(s.isCleared)
        s.clear() // idempotent
        assertEquals(4, log.size)
        val late = runCatching { s.onClear { } }.exceptionOrNull()
        assertTrue(late is IllegalStateException && late.message!!.contains("already cleared"))
        val work = runCatching { s.coroutineScope }.exceptionOrNull()
        assertTrue(work is IllegalStateException && work.message!!.contains("cannot start new work"))
    }

    @Test fun clearReportsFirstErrorOnceWithOthersSuppressed() {
        val errors = ArrayList<Throwable>()
        Hooks.onError = { errors += it }
        val s = RememberScope("errs")
        s.onClear { log += "a" }
        s.onClear { throw IllegalStateException("one") }
        s.onClear { throw IllegalStateException("two") }
        s.clear()
        assertEquals(listOf("a"), log)
        assertEquals(1, errors.size)
        assertEquals("two", errors[0].message) // reverse order: last registered runs first
        assertEquals(listOf("one"), errors[0].suppressed.map { it.message })
    }

    @Test fun coroutineFailureInScopeGoesToHooksOnError() {
        val errors = ArrayList<Throwable>()
        Hooks.onError = { errors += it }
        val s = RememberScope("co")
        s.coroutineScope.launch { throw IllegalStateException("co-fail") }
        assertEquals(listOf("co-fail"), errors.map { it.message })
        s.clear()
    }

    @Test fun defaultOnErrorRethrows() {
        val e = runCatching { Hooks.onError(IllegalStateException("x")) }.exceptionOrNull()
        assertEquals("x", e?.message)
    }

    // ---------------------------------------------------------------- HooksViewModel (owner)
    @Test fun ownerClearClearsTopScopesInReverseOrder_errorsAggregatedAndOnErrorRestored() {
        val errors = ArrayList<Throwable>()
        val handler: (Throwable) -> Unit = { errors += it }
        Hooks.onError = handler
        val owner = FakeOwner()
        host.show(owner) {
            customRemember { rememberEffect { { log += "first"; throw IllegalStateException("e1") } } }
            customRemember { rememberEffect { { log += "second"; throw IllegalStateException("e2") } } }
            customRemember { rememberEffect { { log += "third" } } }
        }
        assertEquals(3, debugTopScopeCount(owner))
        host.hide()
        owner.pop()
        assertEquals(listOf("third", "second", "first"), log)
        assertEquals(1, errors.size)
        assertEquals("e2", errors[0].message)
        assertEquals(listOf("e1"), errors[0].suppressed.map { it.message })
        assertSame(handler, Hooks.onError)
    }

    @Test fun ownerClearWithoutErrorsReportsNothing() {
        var reported = 0
        Hooks.onError = { reported++ }
        val owner = FakeOwner()
        host.show(owner) { rememberState { 1 } }
        host.hide()
        owner.pop()
        assertEquals(0, reported)
        // the ViewModel is gone with the store; a new lookup creates a fresh, empty one
        assertEquals(0, debugTopScopeCount(owner))
    }

    @Test fun sameContentUnderTwoOwnersIsIndependent() {
        val o1 = FakeOwner(); val o2 = FakeOwner()
        var s: MutableState<Int>? = null
        val content: @Composable () -> Unit = { s = rememberState { 0 } }
        host.show(o1, content); val first = s!!; first.value = 1
        host.show(o2, content); val second = s!!
        assertNotSame(first, second)
        assertEquals(0, second.value)
        host.show(o1, content)
        assertSame(first, s)
    }

    @Test fun hooksViewModelDirect() {
        val vm = HooksViewModel()
        val a = vm.top(1, "a")
        assertSame(a, vm.top(1, "ignored"))
        vm.top(2, "b")
        assertEquals(2, vm.topCount())
        vm.onCleared()
        assertTrue(a.isCleared)
        assertEquals(0, vm.topCount())
    }

    // ---------------------------------------------------------------- no owner (Preview / inspection)
    @Test fun withoutOwner_stateKeptAcrossRecomposition_clearedWhenForgotten() {
        val tick = mutableIntStateOf(0)
        val show = mutableStateOf(true)
        val seen = ArrayList<MutableState<Int>>()
        var scope: RememberScope? = null
        host.showWithoutOwner {
            tick.intValue
            if (show.value) {
                seen += customRemember {
                    scope = ScopeStack.peek()
                    rememberEffect { log += "run"; { log += "clean" } }
                    rememberState { 0 }
                }
            }
        }
        host.act { tick.intValue++ }
        assertSame(seen[0], seen[1])
        assertEquals(listOf("run"), log)
        host.act { show.value = false }
        assertTrue("forgotten by remember -> cleared", scope!!.isCleared)
        assertEquals(listOf("run", "clean"), log)
        host.act { show.value = true }
        assertNotSame(seen[0], seen.last())
        host.hide()
        assertEquals(listOf("run", "clean", "run", "clean"), log)
    }

    @Test fun inspectionScopeObserverCallbacks() {
        val remembered = InspectionScope(RememberScope("i1"))
        remembered.onRemembered()
        assertFalse(remembered.scope.isCleared)
        remembered.onForgotten()
        assertTrue(remembered.scope.isCleared)
        val abandoned = InspectionScope(RememberScope("i2"))
        abandoned.onAbandoned()
        assertTrue(abandoned.scope.isCleared)
    }

    @Test fun withoutOwner_abandonedCompositionClearsScope() {
        var scope: RememberScope? = null
        runCatching {
            host.showWithoutOwner {
                customRemember { scope = ScopeStack.peek() }
                throw RuntimeException("abandon")
            }
        }
        assertTrue(scope!!.isCleared)
    }

    // ---------------------------------------------------------------- JVM equivalents of androidTest LifecycleTest
    class Form(val count: MutableState<Int>, val inner: MutableState<Int>, val scope: RememberScope, val innerScope: RememberScope)

    private var jobCancelled: Boolean? = null

    @Composable fun rememberInnerForm(tag: String): Pair<MutableState<Int>, RememberScope> = customRemember {
        val s = rememberState { 0 }
        rememberEffect { log += "run:inner:$tag"; { log += "clean:inner:$tag" } }
        s to ScopeStack.peek()!!
    }

    @Composable fun rememberForm(tag: String): Form = customRemember {
        val count = rememberState { 0 }
        val (inner, innerScope) = rememberInnerForm(tag)
        rememberEffect {
            log += "run:$tag"
            val job = launch { awaitCancellation() }
            val cleanup = { jobCancelled = job.isCancelled; log += "clean:$tag" }
            cleanup
        }
        Form(count, inner, ScopeStack.peek()!!, innerScope)
    }

    /** a NavHost stand-in: each back-stack entry is a FakeOwner, the visible one is composed by [host] */
    private inner class Nav {
        val stack = ArrayList<Pair<String, FakeOwner>>()
        val forms = HashMap<FakeOwner, Form>()
        fun screen(route: String, owner: FakeOwner): @Composable () -> Unit = { forms[owner] = rememberForm(route) }
        fun navigate(route: String) { val o = FakeOwner(); stack += route to o; host.show(o, screen(route, o)) }
        fun pop() { val (_, o) = stack.removeAt(stack.size - 1); host.hide(); o.pop(); stack.last().let { (r, top) -> host.show(top, screen(r, top)) } }
        fun recreate() { val (r, o) = stack.last(); host.hide(); host.show(o, screen(r, o)) }
        fun finish() { host.hide(); stack.asReversed().forEach { it.second.pop() }; stack.clear() }
        val top: Form get() = forms[stack.last().second]!!
    }

    @Test fun lifecycle_backNavigationKeepsStateAndDoesNotRerunEffects() {
        val nav = Nav()
        nav.navigate("a")
        val a = nav.top
        host.act { a.count.value = 3 }
        nav.navigate("b")
        nav.pop()
        assertSame(a.count, nav.top.count)
        assertEquals(3, nav.top.count.value)
        assertEquals(listOf("run:inner:a", "run:a"), log.filter { it.startsWith("run") && it.endsWith("a") })
        assertFalse(log.any { it.startsWith("clean") && it.endsWith("a") })
        assertEquals("popped b cleaned child-first", listOf("clean:inner:b", "clean:b"), log.filter { it.startsWith("clean") })
    }

    private fun topRefs(nav: Nav): Pair<List<RememberScope>, List<WeakReference<Any>>> {
        val b = nav.top
        return listOf(b.scope, b.innerScope) to listOf(WeakReference<Any>(b.count), WeakReference<Any>(b.inner))
    }

    @Test fun lifecycle_popClearsScopeTreeChildFirstAndJobBeforeCleanup_noLeak() {
        val nav = Nav()
        nav.navigate("a")
        nav.navigate("b")
        val (scopes, refs) = topRefs(nav)
        nav.forms.clear()
        nav.pop()
        assertTrue(scopes.all { it.isCleared })
        assertEquals(listOf("clean:inner:b", "clean:b"), log.filter { it.startsWith("clean") })
        assertEquals(true, jobCancelled)
        assertTrue(scopes.all { it.debugSlotCount() == 0 && it.debugChildCount() == 0 })
        assertTrue("state of popped screen collected", gcUntil { refs.all { it.get() == null } })
    }

    @Test fun lifecycle_recreateKeepsState() {
        val nav = Nav()
        nav.navigate("a")
        val a = nav.top
        host.act { a.count.value = 7 }
        nav.recreate()
        assertSame(a.count, nav.top.count)
        assertEquals(7, nav.top.count.value)
        assertFalse(log.any { it.startsWith("clean") })
        assertEquals(2, log.count { it.startsWith("run") })
    }

    @Test fun lifecycle_sameRouteTwiceDistinctState() {
        val nav = Nav()
        nav.navigate("a")
        nav.navigate("b")
        val b1 = nav.top.count
        host.act { b1.value = 1 }
        nav.navigate("b")
        val b2 = nav.top.count
        assertNotSame(b1, b2)
        assertEquals(0, b2.value)
        nav.pop()
        assertSame(b1, nav.top.count)
        assertEquals(1, nav.top.count.value)
    }

    @Test fun lifecycle_finishClearsEverything() {
        val nav = Nav()
        nav.navigate("a")
        val a = nav.top
        nav.finish()
        assertTrue(a.scope.isCleared)
        assertEquals(listOf("clean:inner:a", "clean:a"), log.filter { it.startsWith("clean") })
    }
}
