package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** rememberEffect, EffectScope, EffectState. */
@OptIn(ExperimentalCoroutinesApi::class)
class EffectTest {
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

    @Test fun noDepsRunsOnceForOwnerLifetime() {
        val owner = FakeOwner()
        val tick = mutableIntStateOf(0)
        host.show(owner) { tick.intValue; rememberEffect { log += "run" } }
        repeat(3) { host.act { tick.intValue++ } }
        assertEquals(listOf("run"), log)
    }

    @Test fun depsComparedByContent_multipleDeps() {
        val owner = FakeOwner()
        val a = mutableStateOf("x")
        val b = mutableIntStateOf(1)
        val tick = mutableIntStateOf(0)
        host.show(owner) {
            tick.intValue
            val av = a.value; val bv = b.intValue
            rememberEffect(av, bv, null) { log += "run:$av$bv" }
        }
        host.act { tick.intValue++ }
        host.act { a.value = "y" }
        host.act { b.intValue = 2 }
        host.act { a.value = String(charArrayOf('y')) } // equal content, new instance
        assertEquals(listOf("run:x1", "run:y1", "run:y2"), log)
    }

    @Test fun stateExposedForInspection() {
        val owner = FakeOwner()
        val dep = mutableIntStateOf(1)
        var scope: RememberScope? = null
        host.show(owner) {
            val d = dep.intValue
            customRemember {
                scope = ScopeStack.peek()
                rememberEffect(d) { launch { awaitCancellation() }; { log += "clean:$d" } }
            }
        }
        val effect = scope!!.effects!!.single()
        assertTrue(effect.deps!!.contentEquals(arrayOf<Any?>(1)))
        assertNotNull(effect.cleanup)
        assertNotNull(effect.scope)
        assertNull("no pending work after commit", effect.pendingBlock)
        assertNull(effect.pendingDeps)
        assertTrue(scope!!.releases == null)
        host.act { dep.intValue = 2 }
        assertTrue(effect.deps!!.contentEquals(arrayOf<Any?>(2)))
        owner.pop()
        assertNull(effect.cleanup)
        assertNull(effect.scope)
        assertNull(scope!!.effects)
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

    @Test fun throwingBlockIsReported_effectStillRerunsOnDepChange() {
        val owner = FakeOwner()
        val errors = ArrayList<Throwable>()
        Hooks.onError = { errors += it }
        val dep = mutableIntStateOf(1)
        host.show(owner) {
            val d = dep.intValue
            rememberEffect(d) { log += "run:$d"; if (d == 1) throw IllegalStateException("block") ; { log += "clean:$d" } }
        }
        assertEquals(listOf("block"), errors.map { it.message })
        host.act { dep.intValue = 2 }
        assertEquals("failed run left no cleanup", listOf("run:1", "run:2"), log)
        owner.pop()
        assertEquals("clean:2", log.last())
    }

    @Test fun cleanupAndNextBlockBothThrow_oneErrorWithSuppressed() {
        val owner = FakeOwner()
        val errors = ArrayList<Throwable>()
        Hooks.onError = { errors += it }
        val dep = mutableIntStateOf(1)
        host.show(owner) {
            val d = dep.intValue
            rememberEffect(d) {
                if (d == 2) throw IllegalStateException("run2")
                val cleanup: () -> Unit = { throw IllegalStateException("clean1") }
                cleanup
            }
        }
        host.act { dep.intValue = 2 }
        assertEquals(1, errors.size)
        assertEquals("clean1", errors[0].message)
        assertEquals(listOf("run2"), errors[0].suppressed.map { it.message })
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

    @Test fun ownerClearedBeforeCommit_pendingEffectDoesNotRun() {
        val owner = FakeOwner()
        host.show(owner) {
            rememberEffect { log += "run" }
            owner.pop() // the owner goes away in the middle of the pass, before SideEffects are applied
        }
        assertEquals(emptyList<String>(), log)
    }

    @Test fun effectScopeJobIsChildOfHookScope_contextCreatedOnce_cancelledOnClear() {
        val owner = FakeOwner()
        var effectScope: CoroutineScope? = null
        var hookScope: RememberScope? = null
        var job: Job? = null
        var sameContext = false
        host.show(owner) {
            customRemember {
                hookScope = ScopeStack.peek()
                rememberEffect {
                    effectScope = this
                    sameContext = coroutineContext === coroutineContext
                    job = launch { awaitCancellation() }
                }
            }
        }
        assertTrue(sameContext)
        val effectJob = effectScope!!.coroutineContext.job
        assertSame(hookScope!!.coroutineScope.coroutineContext.job, effectJob.parent)
        assertTrue(job!!.isActive)
        owner.pop()
        assertTrue(job!!.isCancelled)
        assertFalse(effectJob.isActive)
    }

    @Test fun effectWithoutCoroutinesDisposesWithoutCreatingAJob() {
        val owner = FakeOwner()
        var scope: RememberScope? = null
        val dep = mutableIntStateOf(1)
        host.show(owner) {
            val d = dep.intValue
            customRemember { scope = ScopeStack.peek(); rememberEffect(d) { log += "run:$d" } }
        }
        host.act { dep.intValue = 2 }
        owner.pop()
        assertEquals(listOf("run:1", "run:2"), log)
        assertTrue(scope!!.isCleared)
    }

    @Test fun failingLaunchInEffectGoesToHooksOnError() {
        val owner = FakeOwner()
        val errors = ArrayList<Throwable>()
        Hooks.onError = { errors += it }
        host.show(owner) { rememberEffect { launch { throw IllegalStateException("launch-fail") } } }
        assertEquals(listOf("launch-fail"), errors.map { it.message })
    }

    @Test fun directEffectStateRunPendingWithoutBlockIsNoop() {
        val scope = RememberScope("direct")
        val e = EffectState(scope)
        e.runPending()
        assertNull(e.deps)
        e.dispose() // no scope, no cleanup
        assertTrue(e.needsRun(arrayOf()))
        scope.clear()
    }
}
