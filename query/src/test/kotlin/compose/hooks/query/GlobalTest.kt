package compose.hooks.query

import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
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

private data class GSession(val name: String)
private data class GTitle(val text: String)
private data class GCounter(val n: Int)
private data class GPop(val v: String)

/** globalState, globalAtom, rememberGlobalState. */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalTest {
    private lateinit var host: Host

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        host = Host()
    }

    @After fun tearDown() {
        host.close()
        Dispatchers.resetMain()
    }

    @Test fun globalStateIsOneInstancePerType() {
        val first = globalState { GSession("Ada") }
        val second = globalState { GSession("Grace") }
        assertSame(first, second)
        assertEquals("Ada", first.read().name)
        val other = globalState { GTitle("Fact") }
        assertNotSame(first as Any, other as Any)
    }

    @Test fun globalAtomByTypeName() {
        val a = globalAtom("test.global.A") { 1 }
        assertSame(a, globalAtom("test.global.A") { 2 })
        assertNotSame(a, globalAtom("test.global.B") { 1 })
        assertEquals(1, a.read())
    }

    @Test fun rememberGlobalStateSharedAcrossScreens_writesRecomposeEveryConsumer() {
        val second = Host()
        try {
            var a: AtomState<GCounter>? = null
            var b: AtomState<GCounter>? = null
            host.show(FakeOwner()) { a = rememberGlobalState { GCounter(0) } }
            second.show(FakeOwner()) { b = rememberGlobalState { GCounter(-1) } }
            assertEquals(0, b!!.value.n)
            host.act { a!!.set(GCounter(1)) }
            second.frame()
            assertEquals(1, a!!.value.n)
            assertEquals(1, b!!.value.n)
            host.act { b!!.update { GCounter(it.n + 1) } }
            second.frame()
            assertEquals(2, a!!.value.n)
            assertEquals(2, b!!.value.n)
        } finally {
            second.close()
        }
    }

    @Test fun globalValueOutlivesOwners_delegateForm() {
        val owner = FakeOwner()
        var seen: GPop? = null
        host.show(owner) { val v by rememberGlobalState { GPop("init") }; seen = v }
        assertEquals("init", seen!!.v)
        host.act { globalState { GPop("?") }.write(GPop("kept")) }
        assertEquals("kept", seen!!.v)
        host.hide(); owner.pop()
        host.show(FakeOwner()) { val v by rememberGlobalState { GPop("again") }; seen = v }
        assertEquals("global: not reset by popping its users", "kept", seen!!.v)
    }
}
