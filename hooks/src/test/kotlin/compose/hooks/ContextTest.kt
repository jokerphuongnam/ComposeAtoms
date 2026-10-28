package compose.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** rememberContextOf, RememberContext.Provider, rememberContext, lending (Lease / ConsumerRef), exceptions. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextTest {
    private lateinit var host: Host
    private val Ctx = rememberContextOf<Box>("Box")

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        host = Host()
        Hooks.onError = { throw it }
    }

    @After fun tearDown() {
        host.close()
        Dispatchers.resetMain()
    }

    @Test fun contextL1toL4() {
        val provOwner = FakeOwner()
        val consOwner = FakeOwner()
        val value = mutableStateOf(Box("a"))
        var seen: Box? = null
        var inner: Box? = null
        var consumerScope: RememberScope? = null
        host.show(provOwner) {
            Ctx.Provider(value.value) {
                customRemember { seen = rememberContext(Ctx); consumerScope = ScopeStack.peek() }
                Ctx.Provider(Box("inner")) { inner = rememberContext(Ctx) }
            }
        }
        assertEquals("L1 consumer sees provider", "a", seen!!.name)
        assertEquals("L4 nearest provider wins", "inner", inner!!.name)
        host.act { value.value = Box("b") }
        assertEquals("L1 update propagates", "b", seen!!.name)
        // L2: consumer drops its lease when its own owner clears
        val ref = consumerScope!!.slots[0] as ConsumerRef<*>
        assertTrue(ref.lease != null)
        assertEquals(ref.lease, Ctx.debugCurrentLease(ref))
        provOwner.pop()
        assertEquals(null, ref.lease)
        // L3: reading a lease whose provider owner was cleared fails fast
        var lease: Lease<Box>? = null
        host.show(consOwner) {
            Ctx.Provider(Box("p")) { lease = Ctx.local.current }
        }
        host.hide()
        consOwner.pop()
        assertTrue(lease!!.revoked)
        val err = runCatching { lease!!.value }.exceptionOrNull()
        assertTrue(err is RevokedContextException)
        assertTrue(err!!.message!!.contains("RememberContext(Box)"))
        lease!!.value = Box("ignored") // writes to a revoked lease are ignored, not thrown
        assertTrue(lease!!.revoked)
    }

    @Test fun missingProviderThrows_defaultUsed() {
        val owner = FakeOwner()
        val err = runCatching { host.show(owner) { rememberContext(Ctx) } }.exceptionOrNull()
        assertTrue(err is MissingProviderException)
        assertTrue(err!!.message!!.contains("No provider for RememberContext(Box)"))
        val withDefault = rememberContextOf("D") { Box("default") }
        var got: Box? = null
        host.show(FakeOwner()) { got = rememberContext(withDefault) }
        assertEquals("default", got!!.name)
    }

    @Test fun providerOverridesDefault() {
        val withDefault = rememberContextOf("D2") { Box("default") }
        var got: Box? = null
        host.show(FakeOwner()) { withDefault.Provider(Box("provided")) { got = rememberContext(withDefault) } }
        assertEquals("provided", got!!.name)
    }

    @Test fun defaultNameAndToString() {
        val c = rememberContextOf<Int>()
        assertEquals("RememberContext", c.name)
        assertEquals("RememberContext(RememberContext)", c.toString())
        assertEquals("RememberContext(Box)", Ctx.toString())
    }

    @Test fun providerLeaseIsStable_sameInstanceUpdatedInPlace() {
        val owner = FakeOwner()
        val value = mutableStateOf(Box("a"))
        val leases = ArrayList<Lease<Box>>()
        val seen = ArrayList<String>()
        host.show(owner) {
            Ctx.Provider(value.value) {
                leases += Ctx.local.current!!
                seen += rememberContext(Ctx).name
            }
        }
        host.act { value.value = Box("b") }
        host.act { value.value = Box("c") }
        assertEquals(1, leases.toSet().size)
        assertEquals(listOf("a", "b", "c"), seen.distinct())
        assertEquals(1, leases[0].consumers)
    }

    @Test fun consumerThatLosesItsProviderFallsBackToDefault_andReleasesLease() {
        val owner = FakeOwner()
        val withDefault = rememberContextOf("Fallback") { Box("default") }
        val provided = mutableStateOf(true)
        var ref: ConsumerRef<*>? = null
        var lease: Lease<Box>? = null
        val seen = ArrayList<String>()
        // an explicit key makes the consumer the same hook instance under both branches
        val consumer: @Composable () -> Unit = {
            customRemember(key = "consumer") {
                seen += rememberContext(withDefault).name
                ref = ScopeStack.peek()!!.slots[0] as ConsumerRef<*>
            }
        }
        host.show(owner) {
            if (provided.value) withDefault.Provider(Box("provided")) { lease = withDefault.local.current; consumer() }
            else consumer()
        }
        assertEquals(1, lease!!.consumers)
        host.act { provided.value = false }
        assertEquals(listOf("provided", "default"), seen)
        assertNull(ref!!.lease)
        assertEquals(0, lease!!.consumers)
    }

    @Test fun consumerRefCountsEachLeaseOnce() {
        val a = Lease(Ctx, Box("a"))
        val b = Lease(Ctx, Box("b"))
        val ref = ConsumerRef<Box>()
        ref.lease = a
        ref.lease = a
        assertEquals(1, a.consumers)
        ref.lease = b
        assertEquals(0, a.consumers)
        assertEquals(1, b.consumers)
        ref.lease = null
        assertEquals(0, b.consumers)
        assertFalse(a.revoked)
        a.revoke()
        assertTrue(a.revoked)
        assertNull(Ctx.debugCurrentLease("not a ref"))
    }

    @Test fun providerScopeClearRevokesLease_consumersUnderSameOwnerReleased() {
        val owner = FakeOwner()
        var lease: Lease<Box>? = null
        host.show(owner) {
            Ctx.Provider(Box("x")) {
                lease = Ctx.local.current
                rememberContext(Ctx)
            }
        }
        assertEquals(1, lease!!.consumers)
        host.hide()
        owner.pop()
        assertTrue(lease!!.revoked)
        assertEquals(0, lease!!.consumers)
    }

    /**
     * BUG (fixed): `default?.invoke() ?: throw MissingProviderException` treated a default that *returns* null as
     * "no default", so a nullable context with a `{ null }` default threw MissingProviderException without a provider.
     */
    @Test fun nullableDefaultReturningNullIsAValidValue() {
        val optional = rememberContextOf<String?>("Optional") { null }
        var got: String? = "unset"
        host.show(FakeOwner()) { got = rememberContext(optional) }
        assertNull(got)
        var provided: String? = null
        host.show(FakeOwner()) { optional.Provider("x") { provided = rememberContext(optional) } }
        assertEquals("x", provided)
    }
}
