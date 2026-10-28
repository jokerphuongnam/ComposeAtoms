package compose.hooks.query

import androidx.compose.runtime.mutableStateOf
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private data class SSession(val name: String)
private data class STitle(val text: String)

/** shareState, shareAtom, rememberShareState, shareUsers, retainShare / releaseShare. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareTest {
    private lateinit var host: Host

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        host = Host()
    }

    @After fun tearDown() {
        host.close()
        Dispatchers.resetMain()
    }

    @Test fun shareStateSplitsByIdAndType() {
        val note = shareState("note") { SSession("Ada") }
        val again = shareState("note") { SSession("Grace") }
        val title = shareState("note") { STitle("Fact") }
        assertSame(note, again)
        assertEquals("Ada", again.read().name)
        assertNotSame(note as Any, title as Any)
        assertNotSame(note, shareState("other-note") { SSession("Ada") })
        note.write(SSession("Grace"))
        assertEquals("Grace", again.read().name)
    }

    @Test fun shareStateIsReleasedWithItsLastUser() {
        val first = shareState("release") { 1 }
        retainShare(first)
        retainShare(first)
        first.write(5)
        assertEquals(2, shareUsers(first))
        releaseShare(first)
        assertEquals(5, shareState("release") { 1 }.read())
        releaseShare(first)
        assertEquals(0, shareUsers(first))
        val next = shareState("release") { 1 }
        assertNotSame(first, next)
        assertEquals(1, next.read())
    }

    @Test fun usersOfUnknownOrStaleAtomsAreZero_releaseOfStaleAtomIsNoop() {
        assertEquals(0, shareUsers(shareState("never-retained") { 0 }))
        val old = shareState("stale") { 0 }
        retainShare(old); releaseShare(old) // entry dropped
        val current = shareState("stale") { 0 }
        assertNotSame(old, current)
        retainShare(current)
        releaseShare(old) // stale handle: must not touch the current entry
        assertEquals(1, shareUsers(current))
        releaseShare(current)
        releaseShare(current) // already gone: no-op, never negative
        assertEquals(0, shareUsers(current))
    }

    @Test fun retainingAnAtomWhoseEntryWasDroppedReRegistersIt() {
        val atom = shareState("re-register") { 0 }
        retainShare(atom); releaseShare(atom)
        retainShare(atom)
        assertEquals(1, shareUsers(atom))
        assertSame("the re-registered atom is the one handed out again", atom, shareState("re-register") { 0 })
        releaseShare(atom)
    }

    private fun weakShare(id: String): WeakReference<Atom<Int>> = WeakReference(shareState(id) { 0 }.also { it.write(9) })

    @Test fun unreferencedPlainAtomIsCollected_nextCallStartsFresh() {
        val ref = weakShare("weak")
        assertTrue(gcUntil { ref.get() == null })
        assertEquals(0, shareState("weak") { 0 }.read())
    }

    @Test fun twoScreensShareOneValue_lastUserPopReleasesIt() {
        val second = Host()
        try {
            val o1 = FakeOwner(); val o2 = FakeOwner()
            var a: AtomState<Int>? = null
            var b: AtomState<Int>? = null
            host.show(o1) { a = rememberShareState("screens") { 1 } }
            second.show(o2) { b = rememberShareState("screens") { 100 } }
            assertEquals("second user sees the existing value, not its own init", 1, b!!.value)
            val atom = shareState("screens") { -1 }
            assertEquals(2, shareUsers(atom))
            host.act { a!!.set(2) }
            second.frame()
            assertEquals(2, b!!.value)
            host.hide(); o1.pop()
            assertEquals(1, shareUsers(atom))
            assertEquals(2, atom.read())
            second.hide(); o2.pop()
            assertEquals(0, shareUsers(atom))
            assertEquals("released: next user starts from create()", 7, shareState("screens") { 7 }.read())
        } finally {
            second.close()
        }
    }

    @Test fun hiddenScreenKeepsItsShare() {
        val owner = FakeOwner()
        var a: AtomState<String>? = null
        var init = "x"
        val content: @androidx.compose.runtime.Composable () -> Unit = { a = rememberShareState("hidden") { init } }
        host.show(owner, content)
        val atom = shareState("hidden") { "?" }
        host.hide()
        assertEquals("on the back stack the owner still uses it", 1, shareUsers(atom))
        init = "y"
        host.show(owner, content) // same call site, same owner: the same hook scope comes back
        assertEquals("x", a!!.value)
        assertEquals(1, shareUsers(atom))
        host.hide(); owner.pop()
        assertEquals(0, shareUsers(atom))
    }

    @Test fun idChangeReleasesOldShareAndRetainsNew() {
        val owner = FakeOwner()
        val id = mutableStateOf("id-a")
        var state: AtomState<String>? = null
        host.show(owner) { state = rememberShareState(id.value) { "init:${id.value}" } }
        val a = shareState("id-a") { "?" }
        assertEquals(1, shareUsers(a))
        host.act { id.value = "id-b" }
        host.frame() // the re-subscribing effect refreshes the slot on commit
        val b = shareState("id-b") { "?" }
        assertEquals(0, shareUsers(shareState("id-a") { "?" }))
        assertEquals(1, shareUsers(b))
        assertEquals("init:id-b", state!!.value)
        host.hide(); owner.pop()
        assertEquals(0, shareUsers(b))
    }
}
