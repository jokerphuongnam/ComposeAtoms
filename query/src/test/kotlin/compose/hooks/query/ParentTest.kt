package compose.hooks.query

import androidx.compose.runtime.mutableStateOf
import compose.hooks.MissingProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private data class PUser(val name: String)
private data class PTheme(val dark: Boolean)
private data class PNever(val x: Int)

/** parentContext, ParentState, rememberParentState. */
@OptIn(ExperimentalCoroutinesApi::class)
class ParentTest {
    private lateinit var host: Host

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        host = Host()
    }

    @After fun tearDown() {
        host.close()
        Dispatchers.resetMain()
    }

    @Test fun parentContextIsOnePerType() {
        assertSame(parentContext<String>("Left"), parentContext<String>("Left"))
        assertNotSame(parentContext<String>("Left"), parentContext<String>("Right"))
        assertSame(parentContext<PUser>(PUser::class.java.name), parentContext<PUser>(PUser::class.java.name))
    }

    @Test fun childReadsParent_missingParentFails() {
        var seen: PUser? = null
        host.show(FakeOwner()) { ParentState(PUser("parent")) { seen = rememberParentState<PUser>() } }
        assertEquals("parent", seen!!.name)
        try {
            host.show(FakeOwner()) { rememberParentState<PNever>() }
            fail("expected MissingProviderException")
        } catch (e: MissingProviderException) {
            assertTrue(e.message!!.contains("ParentState<"))
        }
    }

    @Test fun nearestProviderWins_typesAreIndependent() {
        var inner: PUser? = null
        var outer: PUser? = null
        var theme: PTheme? = null
        host.show(FakeOwner()) {
            ParentState(PTheme(dark = true)) {
                ParentState(PUser("outer")) {
                    outer = rememberParentState<PUser>()
                    ParentState(PUser("inner")) {
                        inner = rememberParentState<PUser>()
                        theme = rememberParentState<PTheme>()
                    }
                }
            }
        }
        assertEquals("outer", outer!!.name)
        assertEquals("inner", inner!!.name)
        assertEquals(true, theme!!.dark)
    }

    @Test fun siblingOutsideProviderDoesNotSeeIt() {
        var inside: PUser? = null
        try {
            host.show(FakeOwner()) {
                ParentState(PUser("scoped")) { inside = rememberParentState<PUser>() }
                rememberParentState<PUser>()
            }
            fail("sibling outside ParentState must not see the value")
        } catch (e: MissingProviderException) {
            assertEquals("scoped", inside!!.name)
        }
    }

    @Test fun newParentValueReachesChildren() {
        val value = mutableStateOf(PUser("v1"))
        var seen: PUser? = null
        host.show(FakeOwner()) { ParentState(value.value) { seen = rememberParentState<PUser>() } }
        assertEquals("v1", seen!!.name)
        host.act { value.value = PUser("v2") }
        assertEquals("v2", seen!!.name)
    }
}
