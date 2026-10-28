package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/** rememberState (lambda + value form), policies, update, destructuring, callback stability. */
@OptIn(ExperimentalCoroutinesApi::class)
class StateTest {
    private lateinit var host: Host

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        host = Host()
        Hooks.onError = { throw it }
    }

    @After fun tearDown() {
        host.close()
        Dispatchers.resetMain()
    }

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

    @Test fun writeTriggersRecompositionOfReaders() {
        val owner = FakeOwner()
        val rendered = ArrayList<Int>()
        var state: MutableState<Int>? = null
        host.show(owner) {
            val s = rememberState { 0 }
            state = s
            rendered += s.value
        }
        host.act { state!!.value = 1 }
        host.act { state!!.value = 2 }
        assertEquals(listOf(0, 1, 2), rendered)
    }

    @Test fun delegatedPropertyForm() {
        val owner = FakeOwner()
        var bump: (() -> Unit)? = null
        val rendered = ArrayList<Int>()
        host.show(owner) {
            var count by rememberState { 10 }
            rendered += count
            bump = { count++ }
        }
        host.act { bump!!() }
        host.act { bump!!() }
        assertEquals(listOf(10, 11, 12), rendered)
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

    @Test fun valueFormInsideCustomRememberUsesTheSharedScope() {
        val owner = FakeOwner()
        var scope: RememberScope? = null
        host.show(owner) {
            customRemember {
                rememberState("a"); rememberState { "b" }
                scope = ScopeStack.peek()
            }
        }
        assertEquals(2, scope!!.debugSlotCount())
        assertEquals(1, debugTopScopeCount(owner))
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

    @Test fun structuralPolicyIsDefault_customPoliciesHonoured() {
        data class P(val x: Int)
        val owner = FakeOwner()
        var structural: MutableState<P>? = null
        var referential: MutableState<P>? = null
        var never: MutableState<Int>? = null
        val counts = IntArray(3)
        host.show(owner) {
            customRemember { val s = rememberState { P(1) }; structural = s; s.value; counts[0]++ }
        }
        host.show(owner) {
            customRemember { val s = rememberState(referentialEqualityPolicy()) { P(1) }; referential = s; s.value; counts[1]++ }
        }
        host.act { referential!!.value = P(1) }
        assertEquals("referential: equal-but-new instance recomposes", 2, counts[1])
        host.show(owner) {
            customRemember { val s = rememberState(neverEqualPolicy()) { 1 }; never = s; s.value; counts[2]++ }
        }
        host.act { never!!.value = 1 }
        assertEquals("neverEqual: same value recomposes", 2, counts[2])
        host.show(owner) {
            customRemember { val s = rememberState { P(1) }; structural = s; s.value; counts[0]++ }
        }
        val before = counts[0]
        host.act { structural!!.value = P(1) }
        assertEquals("structural: equal value skipped", before, counts[0])
    }

    @Test fun updateOnPlainMutableState() {
        val s = mutableStateOf(2)
        s.update { it * 21 }
        assertEquals(42, s.value)
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
