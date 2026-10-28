package compose.hooks.query

import androidx.compose.runtime.getValue
import compose.hooks.query.test.runQueryTest
import compose.hooks.test.runHookTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private data class QUser(val name: String)
private data class QSess(val user: String?)
private data class QOther(val n: Int)

/** QueryConfig, QueryClient (clear, seed, cacheState, globals), the Default delegations, LocalQueryClient. */
@OptIn(ExperimentalCoroutinesApi::class)
class QueryClientTest {
    private fun virtual(block: suspend TestScope.(QueryClient) -> Unit) = runTest {
        val client = QueryClient(QueryConfig(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime })
        try { block(client) } finally { client.clear() }
    }

    // ---- QueryConfig ----

    @Test fun defaults_copy_parentJob_global() {
        val d = QueryConfig()
        assertSame(Dispatchers.Default, d.context)
        val t = d.nowMillis()
        assertTrue(t <= System.currentTimeMillis() && System.currentTimeMillis() - t < 10_000)
        val c = d.copy(nowMillis = { 7 })
        assertSame(Dispatchers.Default, c.context)
        assertEquals(7, c.nowMillis())
        assertSame(Dispatchers.Unconfined, c.copy(context = Dispatchers.Unconfined).context)
        val parent = Job()
        val child = QueryConfig(parent)
        assertTrue(child.scope.coroutineContext[Job]!!.isActive)
        parent.cancel()
        assertFalse("cache work is a child of a Job in the context", child.scope.coroutineContext[Job]!!.isActive)
        val previous = QueryConfig.global
        try {
            QueryConfig.global = c
            assertSame("global is the Default client's config", c, QueryClient.Default.config)
        } finally {
            QueryConfig.global = previous
        }
        assertSame(previous, QueryClient.Default.config)
    }

    @Test fun fetch_stale_gc_allOnVirtualTime() = virtual { client ->
        var calls = 0
        fun q() = cache<String>("v-all", staleTime = 30.seconds, gcTime = 1.minutes) { calls++; delay(1_000); "v$calls" }
        val first = q().observe(client)
        runCurrent()
        assertTrue(q().snapshot(client).isFetching)
        advanceTimeBy(999); runCurrent()
        assertTrue("fetch delay is virtual", q().snapshot(client).isPending)
        advanceTimeBy(1); runCurrent()
        assertEquals("v1", q().snapshot(client).data)
        assertEquals(1_000L, currentTime)
        advanceTimeBy(29_000)
        val second = q().observe(client); runCurrent()
        assertEquals("fresh by the configured clock", 1, calls)
        advanceTimeBy(1_000)
        val third = q().observe(client); runCurrent()
        assertEquals("stale by the configured clock: refetch", 2, calls)
        advanceUntilIdle()
        listOf(first, second, third).forEach { it.release() }
        advanceTimeBy(59_999); runCurrent()
        assertTrue(q().entryIn(client) != null)
        advanceTimeBy(1); runCurrent()
        assertNull("evicted after gcTime of virtual time", q().entryIn(client))
    }

    // ---- clients are isolated ----

    @Test fun twoClientsNeverShareEntriesOrConfig() = runTest {
        val a = QueryClient(QueryConfig(StandardTestDispatcher(testScheduler)) { 0 })
        val b = QueryClient(QueryConfig(StandardTestDispatcher(testScheduler)) { 0 })
        var calls = 0
        val q = cache<String>("iso") { calls++; "v$calls" }
        q.observe(a); q.observe(b); advanceUntilIdle()
        assertEquals("one fetch per client", 2, calls)
        assertEquals("v1", q.snapshot(a).data)
        assertEquals("v2", q.snapshot(b).data)
        a.clear()
        assertTrue(q.snapshot(a).isPending)
        assertEquals("v2", q.snapshot(b).data)
        assertTrue("Default untouched", q.snapshot(QueryClient.Default).isPending)
        b.clear()
    }

    @Test fun anExplicitClientOnTheHandleWins() = virtual { client ->
        val q = cache<String>("explicit", client = client) { "c" }
        q.refetch(); advanceUntilIdle()
        assertEquals("c", q.snapshot().data)
        assertEquals("c", client.cacheState<String>("explicit").data)
        assertTrue(QueryClient.Default.cacheState<String>("explicit").isPending)
        assertTrue("another type", client.cacheState<Int>("explicit").isPending)
    }

    // ---- clear ----

    @Test fun clearCancelsInFlight_resetsAndNotifiesOnce_handlesStayValid() = virtual { client ->
        var calls = 0
        val q = cache<String>("c-clear", gcTime = 1.minutes) { calls++; delay(100); "u$calls" }
        val o = q.observe(client); advanceUntilIdle()
        val old = o.entry!!
        var notified = 0
        old.subscribe { notified++ }
        q.refetch(client); runCurrent()
        notified = 0
        client.clear()
        assertEquals(1, notified)
        old.snapshot().let { assertTrue(it.isPending); assertFalse(it.isFetching); assertNull(it.data) }
        o.release() // releasing a detached lease does nothing
        old.fetch(force = true); runCurrent()
        assertFalse("a detached entry never fetches", old.snapshot().isFetching)
        advanceUntilIdle()
        assertEquals("the in-flight fetch was cancelled", 2, calls)
        q.observe(client); advanceUntilIdle()
        assertEquals("the handle leases a new entry", "u3", q.snapshot(client).data)
    }

    @Test fun clearWhileANonCancellableFetchFinishes_resultIsDropped() = virtual { client ->
        val ok = cache<String>("c-nc-ok") { withContext(NonCancellable) { delay(10) }; "late" }
        val bad = cache<String>("c-nc-err") { withContext(NonCancellable) { delay(10) }; error("late failure") }
        val a = ok.observe(client); val b = bad.observe(client); runCurrent()
        client.clear()
        advanceUntilIdle()
        assertNull(a.entry!!.snapshot().data)
        assertNull(b.entry!!.snapshot().error)
    }

    @Test fun clearCancelsAPendingGcCountdown_andEmptiesTheSyncCache() = virtual { client ->
        var calls = 0
        val f: () -> Int = { ++calls }
        assertEquals(1, cachedValue("c-sync", staleTime = 1.minutes, gcTime = 1.minutes, client = client, fetch = f))
        assertEquals(1, cachedValue("c-sync", staleTime = 1.minutes, gcTime = 1.minutes, client = client, fetch = f))
        client.clear()
        assertEquals(2, cachedValue("c-sync", staleTime = 1.minutes, gcTime = 1.minutes, client = client, fetch = f))
        advanceTimeBy(2.minutes.inWholeMilliseconds); runCurrent()
        assertNull(cache<Int>("c-sync") { 0 }.entryIn(client))
    }

    // ---- seed ----

    @Test fun seedIsFreshForItsWindowRegardlessOfLeases() = virtual { client ->
        client.seedCache("s-user", QUser("seeded"), freshFor = 10.seconds)
        var calls = 0
        val q = cache("s-user") { calls++; QUser("real") }
        val first = q.observe(client); runCurrent()
        assertEquals("fresh seed: staleTime 0 does not refetch", 0, calls)
        assertEquals(QUser("seeded"), q.snapshot(client).data)
        advanceTimeBy(10_000)
        q.observe(client); runCurrent()
        assertEquals("seed went stale", 1, calls)
        q.observe(client); runCurrent()
        assertEquals("real data follows staleTime 0", 2, calls)
        first.release()
    }

    @Test fun seedInfiniteByDefault_refetchReplacesIt_syncCacheReadsIt() = virtual { client ->
        client.seedCache("s-inf", "seed")
        advanceTimeBy(365L * 24 * 3600 * 1000)
        var calls = 0
        val q = cache<String>("s-inf") { calls++; "real" }
        q.observe(client); runCurrent()
        assertEquals(0, calls)
        val sync: () -> String = { "sync-fetch" }
        assertEquals("seed", cachedValue("s-inf", gcTime = 1.minutes, client = client, fetch = sync))
        q.refetch(client); runCurrent()
        assertEquals("real", q.snapshot(client).data)
        assertEquals(1, calls)
    }

    @Test fun seedCancelsInFlightFetch_andAPendingGcCountdown() = virtual { client ->
        val q = cache<String>("s-race", gcTime = 1.minutes) { delay(100); "fetched" }
        val o = q.observe(client); runCurrent()
        assertTrue(q.snapshot(client).isFetching)
        client.seedCache("s-race", "seeded")
        advanceUntilIdle()
        assertEquals("the cancelled fetch does not clobber the seed", "seeded", q.snapshot(client).data)
        assertFalse(q.snapshot(client).isFetching)
        o.release()
        advanceTimeBy(30_000)
        client.seedCache("s-race", "seeded-again")
        advanceTimeBy(5.minutes.inWholeMilliseconds); runCurrent()
        assertSame(o.entry, q.entryIn(client))
        assertEquals("seeded-again", q.snapshot(client).data)
    }

    @Test fun seedUnobservedIsKept_thenGcAppliesAfterUse() = virtual { client ->
        client.seedCache("s-gc", 1)
        advanceTimeBy(60.minutes.inWholeMilliseconds); runCurrent()
        val q = cache<Int>("s-gc", gcTime = Duration.ZERO) { 2 }
        assertEquals("not collected while unobserved", 1, q.snapshot(client).data)
        q.observe(client).release()
        assertNull("observed and released with gcTime 0: evicted", q.entryIn(client))
    }

    @Test fun seedRejectsNegativeFreshFor() {
        try { QueryClient().seedCache("s-neg", 1, freshFor = (-1).seconds); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("negative"))
        }
    }

    // ---- the Default client and the pre-client API ----

    @Test fun defaultDelegations() = runTest {
        val previous = QueryConfig.global
        QueryConfig.global = QueryConfig(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        try {
            seedCache("d-seed", "s")
            assertEquals("s", QueryClient.Default.cacheState<String>("d-seed").data)
            CacheRegistry.seed("d-seed2", String::class.java.name, "s2", Duration.INFINITE)
            assertEquals("s2", cache<String>("d-seed2") { "?" }.snapshot().data)
            CacheRegistry.store("d-store", String::class.java.name, "st", Duration.ZERO, 1.minutes)
            assertEquals("st", CacheRegistry.fresh<String>("d-store", String::class.java.name, 1.minutes, 1.minutes))
            assertNull(CacheRegistry.fresh<String>("d-missing", String::class.java.name, 1.minutes, 1.minutes))
            val leased = CacheRegistry.lease("d-lease", String::class.java.name, 1.minutes, 1.minutes) { "l" }
            assertEquals(cache<String>("d-lease") { "x" }, leased)
            leased.refetch(); advanceUntilIdle()
            assertEquals("l", leased.snapshot().data)
            val f: () -> Int = { 3 }
            assertEquals(3, cachedValue("d-sync", gcTime = 1.minutes, fetch = f))
            CacheRegistry.clear()
            assertTrue(leased.snapshot().isPending)
            assertTrue(QueryClient.Default.cacheState<String>("d-seed").isPending)
        } finally {
            QueryClient.Default.clear()
            QueryConfig.global = previous
        }
    }

    // ---- global states per client ----

    @Test fun resetGlobalStates_forgetsValues_notifiesSubscribers_reRegistersCreate() = runHookTest {
        val atom = globalState { QSess(null) }
        atom.write(QSess("alice"))
        val seen = renderHook { val s by rememberGlobalState { QSess(null) }; s }
        assertEquals(QSess("alice"), seen.current)
        val untouched = globalState { QOther(0) }
        seen.act { resetGlobalStates() }
        assertEquals("subscriber recomposed with the initial value", QSess(null), seen.current)
        assertEquals("old handle stays valid", QSess(null), atom.read())
        assertNotSame("type registry cleared", untouched, globalState { QOther(1) })
        assertEquals("the new create is used", QOther(1), globalState { QOther(2) }.read())
        val share = shareAtom("q-share") { 5 }
        share.write(6)
        resetGlobalStates()
        assertEquals("share atoms are not affected", 6, share.read())
        onTeardown { resetGlobalStates() }
    }

    @Test fun globalAndShareStatesArePerClient() {
        val a = QueryClient()
        val b = QueryClient()
        globalState(a) { QSess("a") }.write(QSess("a2"))
        assertEquals(QSess(null), globalState(b) { QSess(null) }.read())
        assertEquals(QSess("a2"), globalState(a) { QSess(null) }.read())
        assertSame(a.globalAtom<Int>("t") { 1 }, a.globalAtom<Int>("t") { 2 })
        shareState("s", a) { 1 }.write(5)
        assertEquals(0, shareState("s", b) { 0 }.read())
        a.resetGlobalStates()
        assertEquals(QSess(null), globalState(a) { QSess(null) }.read())
    }

    // ---- composition: LocalQueryClient, QueryClientProvider, rememberQueryClient ----

    @Test fun localQueryClientDefaultsToDefault_providerOverrides() = runHookTest {
        val client = QueryClient()
        assertSame(QueryClient.Default, renderHook { LocalQueryClient.current }.current)
        assertSame(client, renderHook(wrapper = { QueryClientProvider(client, it) }) { LocalQueryClient.current }.current)
    }

    @Test fun rememberQueryClientLivesWithTheOwner_clearedOnPop() = runQueryTest {
        val entry = backStackEntry()
        val made = renderHook(owner = entry) { rememberQueryClient(QueryConfig(mainDispatcher) { currentTime }) }
        val client = made.current
        client.seedCache("rq", 1)
        globalState(client) { QOther(0) }.write(QOther(9))
        made.recreate()
        assertSame("kept across recreation", client, made.current)
        entry.pop()
        assertTrue("cleared on pop", client.cacheState<Int>("rq").isPending)
        assertEquals(QOther(0), globalState(client) { QOther(0) }.read())
    }

    @Test fun rememberGlobalAndShareStateReadTheProvidedClient() = runQueryTest {
        globalState(client) { QUser("client") }
        globalState { QUser("default") }
        val seen = renderHook { rememberGlobalState { QUser("?") }.value }
        assertEquals(QUser("client"), seen.current)
        val held = shareState("sh", client) { 7 } // held: an unreferenced share atom may be collected
        val shared = renderHook { rememberShareState("sh") { 0 }.value }
        assertEquals(7, shared.current)
        assertEquals(7, held.read())
    }
}
