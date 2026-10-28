package compose.hooks.query

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import compose.hooks.customRemember
import compose.hooks.rememberEffect
import compose.hooks.rememberState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test

internal data class QSession(val name: String)
internal data class QNote(val text: String)
internal data class QProfile(val name: String)

/** rememberAtom, plus the query hooks used together inside / outside customRemember. */
@OptIn(ExperimentalCoroutinesApi::class)
class RememberTest {
    private lateinit var host: Host
    private lateinit var previousScope: CoroutineScope

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        previousScope = cacheScope
        cacheScope = CoroutineScope(Dispatchers.Unconfined)
        host = Host()
    }

    @After fun tearDown() {
        host.close()
        cacheScope = previousScope
        Dispatchers.resetMain()
    }

    private fun atom(init: Int = 0) = Atom(AtomStore(), "r", { init })

    @Test fun writeRecomposesReader() {
        val a = atom(1)
        var seen = -1
        var passes = 0
        host.show(FakeOwner()) { passes++; seen = rememberAtom(a).value }
        assertEquals(1, seen)
        host.act { a.write(2) }
        assertEquals(2, seen)
        assertEquals(2, passes)
    }

    @Test fun setterWritesTheAtom() {
        val a = atom()
        var state: AtomState<Int>? = null
        host.show(FakeOwner()) { state = rememberAtom(a) }
        host.act { state!!.set(5) }
        assertEquals(5, a.read())
        assertEquals(5, state!!.value)
        host.act { state!!.update { it + 1 } }
        assertEquals(6, state!!.value)
    }

    @Test fun writeBetweenCompositionAndSubscriptionIsNotLost() {
        val a = atom()
        var seen = -1
        host.show(FakeOwner()) {
            seen = rememberAtom(a).value
            // runs on commit before rememberAtom's effect subscribes (effects run in order)
            if (seen == 0) rememberEffect(Unit) { a.write(42) }
        }
        host.frame()
        assertEquals(42, seen)
    }

    @Test fun subscriptionLastsWhileHidden_endsOnPop() {
        val owner = FakeOwner()
        val a = atom()
        var state: AtomState<Int>? = null
        host.show(owner) { state = rememberAtom(a) }
        host.hide()
        a.write(3) // while on the back stack: slot is updated by the live subscription
        host.show(owner) { state = rememberAtom(a) }
        assertEquals("no flash of the old value", 3, state!!.value)
        host.hide(); owner.pop()
        val log = ArrayList<Int>()
        val probe = a.subscribe { log += a.read() }
        a.write(4)
        probe()
        assertEquals(listOf(4), log)
    }

    @Test fun twoConsumersSeeOneAtom() {
        val a = atom()
        var x = -1; var y = -1
        host.show(FakeOwner()) {
            x = rememberAtom(a).value
            customRemember { y = rememberAtom(a).value }
        }
        host.act { a.write(9) }
        assertEquals(9, x); assertEquals(9, y)
    }

    internal class Form(val session: QSession, val profile: CacheResult<QProfile>, val name: String, val note: AtomState<QNote>)

    private var fetches = 0
    private val profileQuery get() = cache<QProfile>("q-profile", staleTime = 60.seconds) { fetches++; QProfile("Grace") }

    @Composable internal fun rememberForm(id: String): Form = customRemember {
        val session by rememberGlobalState { QSession("Ada") }
        val profile = rememberCache(profileQuery)
        var name by rememberState("")
        rememberEffect(profile.data) { profile.data?.let { name = it.name } }
        val note = rememberShareState(id) { QNote("") }
        Form(session, profile, name, note)
    }

    private fun runForm(nested: Boolean) {
        val owner = FakeOwner()
        val other = FakeOwner()
        var a: Form? = null
        var b: AtomState<QNote>? = null
        val id = "note-$nested"
        val content: @Composable () -> Unit = {
            if (nested) a = rememberForm(id) else {
                // same hooks called bare (no customRemember)
                val session by rememberGlobalState { QSession("Ada") }
                val profile = rememberCache(profileQuery)
                var name by rememberState("")
                rememberEffect(profile.data) { profile.data?.let { name = it.name } }
                val note = rememberShareState(id) { QNote("") }
                a = Form(session, profile, name, note)
            }
        }
        host.show(owner, content)
        host.frame()
        assertEquals("Ada", a!!.session.name)
        assertEquals("Grace", a!!.profile.data?.name)
        assertEquals("Grace", a!!.name)
        val second = Host()
        second.show(other) { b = rememberShareState(id) { QNote("other-init") } }
        assertEquals("", b!!.value.text)
        host.act { a!!.note.set(QNote("hello")) }
        second.frame()
        assertEquals("hello", b!!.value.text)
        assertEquals("hello", a!!.note.value.text)
        val atom = shareState(id) { QNote("x") }
        assertEquals(2, shareUsers(atom))
        val fetched = fetches
        host.show(owner, content)
        assertEquals("reshow within staleTime: no refetch", fetched, fetches)
        assertEquals("hello", a!!.note.value.text)
        host.act { globalState { QSession("?") }.write(QSession("Bob")) }
        assertEquals("Bob", a!!.session.name)
        host.hide(); owner.pop()
        assertEquals(1, shareUsers(atom))
        second.hide(); other.pop(); second.close()
        assertEquals(0, shareUsers(atom))
        assertNotSame(atom, shareState(id) { QNote("fresh") })
        assertEquals("fresh", shareState(id) { QNote("fresh") }.read().text)
        globalState { QSession("?") }.write(QSession("Ada"))
    }

    @Test fun queryHooksInsideCustomRemember() = runForm(nested = true)
    @Test fun queryHooksOutsideCustomRemember() = runForm(nested = false)
}
