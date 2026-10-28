package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import compose.hooks.test.runHookTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * rememberState (lambda + value form), policies, update, destructuring, callback stability.
 * Written with hooks-test (dogfooding): runHookTest fails on any Hooks.onError report.
 */
class StateTest {
    @Test fun stateSurvivesRecompositionReshowAndRecreate_initOnce() = runHookTest {
        val owner = backStackEntry()
        var inits = 0
        val result = renderHook(owner) { rememberState { inits++; 1 } }
        val first = result.current
        result.act { first.value = 5 }
        result.recreate() // rotation: new composition, same owner
        assertSame(first, result.current)
        assertEquals(5, result.current.value)
        assertEquals(1, inits)
        owner.pop()
        val other = renderHook { rememberState { inits++; 1 } }
        assertNotSame(first, other.current)
        assertEquals(2, inits)
    }

    @Test fun writeTriggersRecompositionOfReaders() = runHookTest {
        val result = renderHook { val s = rememberState { 0 }; s to s.value }
        result.act { result.current.first.value = 1 }
        result.act { result.current.first.value = 2 }
        assertEquals(listOf(0, 1, 2), result.history.map { it.second })
    }

    @Test fun delegatedPropertyForm() = runHookTest {
        val result = renderHook {
            var count by rememberState { 10 }
            count to { count++ }
        }
        result.act { result.current.second() }
        result.act { result.current.second() }
        assertEquals(listOf(10, 11, 12), result.history.map { it.first })
    }

    @Test fun valueFormStoresFirstValue_argumentStillEvaluatedEachPass() = runHookTest {
        var evaluated = 0
        fun arg(): String { evaluated++; return "v$evaluated" }
        val result = renderHookWithProps(0) { _ -> rememberState(arg()) }
        result.rerender(1)
        result.rerender(2)
        assertEquals("v1", result.current.value)
        assertEquals(3, evaluated) // documented: without the compiler plugin the argument runs every pass
        assertEquals(3, result.recomposeCount)
    }

    @Test fun valueFormInsideCustomRememberUsesTheSharedScope() = runHookTest {
        val owner = backStackEntry()
        val result = renderHook(owner) {
            customRemember {
                rememberState("a"); rememberState { "b" }
                ScopeStack.peek()!!
            }
        }
        assertEquals(2, result.current.debugSlotCount())
        assertEquals(1, owner.hookScopeCount)
    }

    @Test fun skipOnEqualAndBatching() = runHookTest {
        var state: MutableState<Int>? = null
        val result = renderHook { val s = rememberState { 0 }; state = s; s.value }
        assertEquals(1, result.recomposeCount)
        result.act { state!!.value = 0 }
        assertEquals("equal write must not recompose", 1, result.recomposeCount)
        result.act { state!!.value = 1; state!!.update { it + 1 }; state!!.update { it + 1 } }
        assertEquals("three writes, one recomposition", 2, result.recomposeCount)
        assertEquals(3, state!!.value)
        val (value, set) = state!!
        assertEquals(3, value)
        result.act { set(4) }
        assertEquals(4, state!!.value)
    }

    @Test fun structuralPolicyIsDefault_customPoliciesHonoured() = runHookTest {
        data class P(val x: Int)
        val owner = backStackEntry()
        val referential = renderHook(owner) { customRemember { rememberState(referentialEqualityPolicy()) { P(1) }.also { it.value } } }
        referential.act { referential.current.value = P(1) }
        assertEquals("referential: equal-but-new instance recomposes", 2, referential.recomposeCount)
        referential.hide()
        val never = renderHook(owner) { customRemember { rememberState(neverEqualPolicy()) { 1 }.also { it.value } } }
        never.act { never.current.value = 1 }
        assertEquals("neverEqual: same value recomposes", 2, never.recomposeCount)
        never.hide()
        val structural = renderHook(owner) { customRemember { rememberState { P(1) }.also { it.value } } }
        structural.act { structural.current.value = P(1) }
        assertEquals("structural: equal value skipped", 1, structural.recomposeCount)
    }

    @Test fun updateOnPlainMutableState() {
        val s = mutableStateOf(2)
        s.update { it * 21 }
        assertEquals(42, s.value)
    }

    // ---------------------------------------------------------------- C2: callbacks capturing hook setters stay stable
    @Composable fun Child(onClick: () -> Unit, count: IntArray) { count[0]++; onClick.hashCode() }

    @Test fun c2CallbackCapturingStateIsStable() = runHookTest {
        val tick = mutableIntStateOf(0)
        val childCount = IntArray(1)
        val result = renderHook {
            tick.intValue
            val s = rememberState { 0 }
            var v by rememberState { 0 }
            Child(onClick = { s.value++; v++ }, count = childCount)
        }
        result.act { tick.intValue++ }
        result.act { tick.intValue++ }
        assertEquals("child skipped: lambda capturing hook state is memoized (strong skipping)", 1, childCount[0])
    }

    /** Data point: MutableState.component2() returns a new lambda each call, so a callback capturing it is not memoized. */
    @Test fun c2CallbackCapturingDestructuredSetterIsNotStable() = runHookTest {
        val tick = mutableIntStateOf(0)
        val childCount = IntArray(1)
        val result = renderHook {
            tick.intValue
            val (_, set) = rememberState { 0 }
            Child(onClick = { set(1) }, count = childCount)
        }
        result.act { tick.intValue++ }
        result.act { tick.intValue++ }
        assertEquals(3, childCount[0])
    }
}
