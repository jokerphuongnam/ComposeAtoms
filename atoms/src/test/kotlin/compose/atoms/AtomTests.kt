package compose.atoms

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomTests {
    @Test
    fun globalStateIsOneInstancePerType() {
        val first = globalState { Session("Ada") }
        val second = globalState { Session("Grace") }
        assertSame(first, second)
        assertEquals("Ada", first.read().name)
        val other = globalState { Title("Fact") }
        assertNotSame(first, other)
    }

    @Test
    fun shareStateSplitsByIdAndType() {
        val note = shareState("note") { Session("Ada") }
        val again = shareState("note") { Session("Grace") }
        val title = shareState("note") { Title("Fact") }
        assertSame(note, again)
        assertEquals("Ada", again.read().name)
        assertNotSame(note, title)
        note.write(Session("Grace"))
        assertEquals("Grace", again.read().name)
    }

    @Test
    fun subscribersSeeWrites() {
        val atom = shareState("clock") { 0 }
        var seen = atom.read()
        val unsubscribe = atom.subscribe { seen = atom.read() }
        atom.write(2)
        assertEquals(2, seen)
        unsubscribe()
        atom.write(3)
        assertEquals(2, seen)
    }

    @Test
    fun parentScopeHidesSiblings() {
        val root = ParentScope().setting("Parent", "root")
        val left = root.setting("Left", "L")
        val right = root.setting("Right", "R")
        assertEquals("root", left.get<String>("Parent"))
        assertEquals("root", right.get<String>("Parent"))
        assertEquals(null, left.get<String>("Right"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cacheJoinsOneFetchAndSplitsTypes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val previous = cacheScope
        cacheScope = CoroutineScope(dispatcher)
        CacheClock.nowMillis = { 0L }
        try {
            var calls = 0
            val text = cache<String>("fact", staleTime = 60.seconds, gcTime = 60.seconds) {
                calls += 1
                "A"
            }
            val number = cache<Int>("fact", staleTime = 60.seconds, gcTime = 60.seconds) {
                calls += 1
                1
            }
            text.retain()
            number.retain()
            advanceUntilIdle()
            assertEquals("A", text.snapshot().data)
            assertEquals(1, number.snapshot().data)
            assertEquals(2, calls)
            val textAgain = cache<String>("fact", staleTime = 60.seconds, gcTime = 60.seconds) {
                calls += 1
                "B"
            }
            textAgain.retain()
            advanceUntilIdle()
            assertEquals("A", textAgain.snapshot().data)
            assertEquals(2, calls)
            CacheClock.nowMillis = { 60_000L }
            text.refetch()
            advanceUntilIdle()
            assertEquals("B", text.snapshot().data)
            assertEquals(3, calls)
        } finally {
            cacheScope = previous
            CacheClock.nowMillis = { System.currentTimeMillis() }
        }
    }
}

private data class Session(val name: String)
private data class Title(val text: String)
