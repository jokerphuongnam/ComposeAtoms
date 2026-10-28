package compose.hooks.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Atom, AtomState, AtomStore (plain, no composition). */
class AtomTest {
    private fun atom(store: AtomStore = AtomStore(), id: Any = "id", create: () -> Int): Atom<Int> = Atom(store, id, create)

    @Test fun readCreatesLazilyAndOnlyOnce() {
        var creates = 0
        val a = atom { creates++; 7 }
        assertEquals(0, creates)
        assertEquals(7, a.read())
        assertEquals(7, a.read())
        assertEquals(1, creates)
    }

    @Test fun writeBeforeReadSkipsCreate_nullIsAValue() {
        var creates = 0
        val a = atom { creates++; 1 }
        a.write(5)
        assertEquals(5, a.read())
        assertEquals(0, creates)
        val n = Atom<String?>(AtomStore(), "n") { "init" }
        n.write(null)
        assertEquals("a stored null is not re-created", null, n.read())
    }

    @Test fun updateAppliesTransform() {
        val a = atom { 2 }
        a.update { it * 10 }
        a.update { it + 1 }
        assertEquals(21, a.read())
    }

    @Test fun subscribersSeeWrites_unsubscribeStops() {
        val a = atom { 0 }
        var seen = a.read()
        val unsubscribe = a.subscribe { seen = a.read() }
        a.write(2)
        assertEquals(2, seen)
        unsubscribe()
        a.write(3)
        assertEquals(2, seen)
        unsubscribe() // idempotent
    }

    @Test fun severalSubscribers_listenerMayUnsubscribeWhileNotified() {
        val a = atom { 0 }
        val log = ArrayList<String>()
        var unsubA: (() -> Unit)? = null
        unsubA = a.subscribe { log += "a${a.read()}"; unsubA!!() }
        val unsubB = a.subscribe { log += "b${a.read()}" }
        a.write(1)
        a.write(2)
        assertEquals(listOf("a1", "b1", "b2"), log)
        unsubB()
        a.write(3)
        assertEquals(3, log.size)
        // resubscribe after the identity's listener set was dropped
        a.subscribe { log += "c${a.read()}" }
        a.write(4)
        assertEquals("c4", log.last())
    }

    @Test fun atomsInOneStoreAreIsolatedByIdentity() {
        val store = AtomStore()
        val x = atom(store, "x") { 1 }
        val y = atom(store, "y") { 2 }
        var yNotified = 0
        y.subscribe { yNotified++ }
        x.write(10)
        assertEquals(2, y.read())
        assertEquals(0, yNotified)
        val x2 = atom(store, "x") { 99 }
        assertEquals("same store + identity = same value", 10, x2.read())
    }

    @Test fun storeRemoveForgetsValueAndListeners() {
        val store = AtomStore()
        var creates = 0
        val a = atom(store) { ++creates }
        var notified = 0
        a.subscribe { notified++ }
        a.write(50)
        store.remove("id")
        assertEquals("re-created after remove", 1, a.read())
        a.write(2)
        assertEquals("listeners were dropped", 1, notified)
    }

    @Test fun atomStateValueSetUpdateDelegateAndDestructuring() {
        val writes = ArrayList<Int>()
        val state = AtomState(3) { writes += it }
        assertEquals(3, state.value)
        state.set(4)
        state.update { it + 10 }
        val delegated by state
        assertEquals(3, delegated)
        val (v, set) = state
        assertEquals(3, v)
        set(1)
        assertEquals(listOf(4, 13, 1), writes)
        assertSame(state.set, state.component2())
    }

    /**
     * BUG (fixed): an unsubscribe handle kept a reference to the listener set it was added to and, when that set became
     * empty, removed whatever set was registered for the identity *now*. After `AtomStore.remove(id)` (share release)
     * or a repeated unsubscribe, a stale handle dropped the listeners of newer subscribers, which then silently stopped
     * receiving writes.
     */
    @Test fun staleUnsubscribeDoesNotDropNewerSubscribers() {
        val store = AtomStore()
        val a = atom(store) { 0 }
        val log = ArrayList<String>()
        val old = a.subscribe { log += "old" }
        store.remove("id")             // e.g. releaseShare() of the last rememberShareState user
        a.subscribe { log += "new" }   // a later subscriber gets a fresh listener set
        old()                          // the earlier subscriber's scope is cleared afterwards
        a.write(1)
        assertEquals(listOf("new"), log)

        val b = atom(AtomStore()) { 0 }
        val first = b.subscribe { log += "first" }
        first()
        b.subscribe { log += "second" }
        first()                        // double unsubscribe (handles are idempotent)
        b.write(1)
        assertEquals(listOf("new", "second"), log)
    }
}
