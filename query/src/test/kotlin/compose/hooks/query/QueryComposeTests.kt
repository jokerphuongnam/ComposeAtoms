package compose.hooks.query

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal data class QSession(val name: String)
internal data class QNote(val text: String)
internal data class QProfile(val name: String)

@OptIn(ExperimentalCoroutinesApi::class)
class QueryComposeTests {
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
        var b: Any? = null
        val id = "note-" + nested
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
        val f = a!!
        assertEquals("Ada", f.session.name)
        assertEquals("Grace", f.profile.data?.name)
        assertEquals("Grace", a!!.name)
        // share: second screen with the same id sees writes
        val second = Host()
        second.show(other) { b = rememberShareState(id) { QNote("other-init") } }
        assertEquals("", (b as AtomState<*>).value.let { (it as QNote).text })
        host.act { a!!.note.set(QNote("hello")) }
        second.frame()
        assertEquals("hello", ((b as AtomState<*>).value as QNote).text)
        assertEquals("hello", a!!.note.value.text)
        val atom = shareState(id) { QNote("x") }
        assertEquals(2, shareUsers(atom))
        // reshow keeps everything; no refetch within staleTime
        val fetched = fetches
        host.show(owner, content)
        assertEquals(fetched, fetches)
        assertEquals("hello", a!!.note.value.text)
        // global write is seen
        host.act { globalState { QSession("?") }.write(QSession("Bob")) }
        assertEquals("Bob", a!!.session.name)
        // pop first owner: share user released, other keeps it
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

    @Test fun rememberCacheEffectNotRestartedEachPass() {
        val owner = FakeOwner()
        val tick = mutableStateOf(0)
        var observers = 0
        host.show(owner) {
            tick.value
            rememberCache(cache<QProfile>("q-eq", staleTime = 60.seconds) { observers++; QProfile("x") })
        }
        host.act { tick.value++ }
        host.act { tick.value++ }
        assertEquals("one fetch: same entry = same deps, effect not re-run", 1, observers)
    }

    @Test fun parentStateOnRememberContext() {
        val owner = FakeOwner()
        var seen: QSession? = null
        var err: Throwable? = null
        host.show(owner) {
            ParentState(QSession("parent")) { seen = rememberParentState<QSession>() }
        }
        assertEquals("parent", seen!!.name)
        try { host.show(FakeOwner()) { rememberParentState<QNote>() } } catch (t: Throwable) { err = t }
        assertTrue(err is compose.hooks.MissingProviderException)
    }
}
