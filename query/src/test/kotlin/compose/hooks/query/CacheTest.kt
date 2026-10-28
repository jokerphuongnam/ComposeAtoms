package compose.hooks.query

import androidx.compose.runtime.mutableStateOf
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private data class CProfile(val name: String)

/** cache (suspend + sync), CacheQuery, CacheResult, rememberCache, CacheRegistry, CacheEntry, CacheClock. */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheTest {
    private lateinit var host: Host
    private lateinit var previousScope: CoroutineScope
    private lateinit var previousClock: () -> Long
    private var now = 0L

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        previousScope = cacheScope
        previousClock = CacheClock.nowMillis
        CacheClock.nowMillis = { now }
        host = Host()
    }

    @After fun tearDown() {
        host.close()
        cacheScope = previousScope
        CacheClock.nowMillis = previousClock
        Dispatchers.resetMain()
    }

    /** cacheScope on the test scheduler: fetches and gc countdowns run in virtual time */
    private fun cacheTest(block: suspend TestScope.() -> Unit) = runTest {
        cacheScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        block()
    }

    private fun identity(key: String) = "$key\u0000${String::class.java.name}"

    @Test fun defaultClockIsWallClock() {
        val before = System.currentTimeMillis()
        val t = previousClock()
        assertTrue(t >= before && t <= System.currentTimeMillis())
        assertTrue("default cacheScope exists", previousScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test fun cacheResultFlags() {
        val p = CacheResult<Int>(null, null, false, CacheStatus.Pending)
        assertTrue(p.isPending); assertFalse(p.isSuccess); assertFalse(p.isError)
        val s = CacheResult(1, null, true, CacheStatus.Success)
        assertFalse(s.isPending); assertTrue(s.isSuccess); assertFalse(s.isError); assertTrue(s.isFetching)
        val e = CacheResult<Int>(null, IllegalStateException("x"), false, CacheStatus.Error)
        assertFalse(e.isPending); assertFalse(e.isSuccess); assertTrue(e.isError); assertEquals("x", e.error!!.message)
    }

    @Test fun cacheQueryHandlesToOneEntryAreEqual() {
        val a = cache<String>("c-eq") { "x" }
        val b = cache<String>("c-eq") { "y" }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, cache<Int>("c-eq") { 1 })
        assertFalse(a.equals("c-eq"))
    }

    @Test fun cacheJoinsOneFetchAndSplitsTypes() = cacheTest {
        var calls = 0
        val text = cache<String>("c-fact", staleTime = 60.seconds, gcTime = 60.seconds, fetch = suspend { calls += 1; "A" })
        val number = cache<Int>("c-fact", staleTime = 60.seconds, gcTime = 60.seconds, fetch = suspend { calls += 1; 1 })
        text.retain(); number.retain()
        advanceUntilIdle()
        assertEquals("A", text.snapshot().data)
        assertEquals(1, number.snapshot().data)
        assertEquals(2, calls)
        val textAgain = cache<String>("c-fact", staleTime = 60.seconds, gcTime = 60.seconds, fetch = suspend { calls += 1; "B" })
        textAgain.retain()
        advanceUntilIdle()
        assertEquals("A", textAgain.snapshot().data)
        assertEquals(2, calls)
        now = 60_000L
        text.refetch()
        advanceUntilIdle()
        assertEquals("B", text.snapshot().data)
        assertEquals(3, calls)
    }

    @Test fun snapshotGoesPendingFetchingSuccess() = cacheTest {
        val q = cache<String>("c-transitions") { delay(100); "v" }
        q.snapshot().let { assertTrue(it.isPending); assertFalse(it.isFetching); assertNull(it.data) }
        var published = 0
        val unsubscribe = q.subscribe { published++ }
        q.retain()
        q.snapshot().let { assertTrue(it.isPending); assertTrue(it.isFetching) }
        assertEquals(1, published)
        advanceUntilIdle()
        q.snapshot().let { assertTrue(it.isSuccess); assertFalse(it.isFetching); assertEquals("v", it.data); assertNull(it.error) }
        assertEquals(2, published)
        unsubscribe()
        q.refetch(); advanceUntilIdle()
        assertEquals("unsubscribed listener is not called", 2, published)
    }

    @Test fun concurrentRetainsShareOneFetch() = cacheTest {
        var calls = 0
        val a = cache<String>("c-dedupe") { calls++; delay(50); "v" }
        a.retain(); runCurrent()
        val b = cache<String>("c-dedupe") { calls++; delay(50); "w" }
        b.retain(); runCurrent()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals("v", b.snapshot().data)
    }

    @Test fun staleDataIsRefetchedOnRetain_freshIsNot() = cacheTest {
        var calls = 0
        val q = cache<String>("c-stale", staleTime = 10.seconds) { calls++; "v$calls" }
        q.retain(); advanceUntilIdle()
        q.release()
        now = 5_000; q.retain(); runCurrent()
        assertEquals("fresh: no fetch", 1, calls)
        q.release()
        now = 20_000; q.retain()
        q.snapshot().let { assertTrue("keeps showing stale data while refetching", it.isSuccess && it.isFetching); assertEquals("v1", it.data) }
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("v2", q.snapshot().data)
    }

    /**
     * BUG (fixed): refetch() cancels the in-flight fetch and starts a new one, but the cancelled coroutine's `finally`
     * ran afterwards and cleared `job`/`isFetching` that now belong to the new fetch. The entry then reported "not
     * fetching" during a fetch, and the next retain() started a duplicate fetch instead of joining it.
     */
    @Test fun refetchCancelsInFlightFetchAndKeepsOwningTheNewOne() = cacheTest {
        var calls = 0
        val started = ArrayList<Int>()
        val q = cache<String>("c-force") { val n = ++calls; started += n; delay(1_000); "v$n" }
        q.retain(); runCurrent()
        advanceTimeBy(100)
        q.refetch()
        runCurrent() // the cancelled first fetch unwinds, the second one starts
        assertTrue("second fetch is still running", q.snapshot().isFetching)
        cache<String>("c-force") { val n = ++calls; started += n; delay(1_000); "v$n" }.retain()
        runCurrent()
        assertEquals("joined the running refetch", listOf(1, 2), started)
        advanceUntilIdle()
        q.snapshot().let { assertEquals("v2", it.data); assertFalse(it.isFetching) }
    }

    @Test fun errorStatusThenRecoveryOnRefetch() = cacheTest {
        var fail = true
        val q = cache<String>("c-error") { if (fail) throw IllegalStateException("boom") else "ok" }
        q.retain(); advanceUntilIdle()
        q.snapshot().let { assertTrue(it.isError); assertEquals("boom", it.error!!.message); assertNull(it.data); assertFalse(it.isFetching) }
        fail = false
        q.refetch(); advanceUntilIdle()
        q.snapshot().let { assertTrue(it.isSuccess); assertNull(it.error); assertEquals("ok", it.data) }
    }

    @Test fun cancellationIsNotAnError() = cacheTest {
        val q = cache<String>("c-cancel") { throw CancellationException("stop") }
        q.retain(); advanceUntilIdle()
        q.snapshot().let { assertTrue(it.isPending); assertNull(it.error); assertFalse(it.isFetching) }
    }

    @Test fun gcTimeZeroEvictsOnLastRelease() = cacheTest {
        val q = cache<String>("c-gc0", gcTime = Duration.ZERO, fetch = suspend { "v" })
        q.retain(); advanceUntilIdle()
        q.release()
        assertNotEquals("evicted: a new lease is a new entry", q, cache<String>("c-gc0") { "v" })
    }

    @Test fun gcTimeDelaysEviction_retainCancelsCountdown_otherObserversKeepEntry() = cacheTest {
        val q = cache<String>("c-gc", gcTime = 1.seconds, fetch = suspend { "v" })
        q.retain(); q.retain(); advanceUntilIdle()
        q.release()
        advanceTimeBy(5_000)
        assertEquals("another observer keeps it", q, cache<String>("c-gc", gcTime = 1.seconds, fetch = suspend { "v" }))
        q.release()
        advanceTimeBy(500)
        q.retain() // comes back before gcTime: countdown cancelled
        advanceTimeBy(5_000)
        assertEquals(q, cache<String>("c-gc", gcTime = 1.seconds, fetch = suspend { "v" }))
        q.release()
        q.release() // extra release never goes negative
        advanceTimeBy(999)
        assertEquals(q, cache<String>("c-gc", gcTime = 1.seconds, fetch = suspend { "v" }))
        advanceTimeBy(2); runCurrent()
        assertNotEquals(q, cache<String>("c-gc", gcTime = 1.seconds, fetch = suspend { "v" }))
    }

    @Test fun evictDuringFetchCancelsIt() = cacheTest {
        var completed = false
        val q = cache<String>("c-evict-fetch", gcTime = Duration.ZERO, fetch = suspend { delay(1_000); completed = true; "v" })
        q.retain(); runCurrent()
        assertTrue(q.snapshot().isFetching)
        q.release()
        advanceUntilIdle()
        assertFalse(completed)
        q.snapshot().let { assertNull(it.data); assertFalse(it.isFetching) }
        assertNotEquals(q, cache<String>("c-evict-fetch") { "v" })
    }

    @Test fun leaseUpdatesBodyAndTimes() = cacheTest {
        val q = cache<String>("c-lease", staleTime = 1.seconds, gcTime = 2.seconds, fetch = suspend { "a" })
        val again = cache<String>("c-lease", staleTime = 1.hours, gcTime = 3.minutes, fetch = suspend { "b" })
        assertSame(q.entry, again.entry)
        assertEquals(1.hours, q.entry.staleTime)
        assertEquals(3.minutes, q.entry.gcTime)
        q.retain(); advanceUntilIdle()
        assertEquals("latest closure is the one that runs", "b", q.snapshot().data)
    }

    @Test fun syncCacheFreshStaleAndGc() = cacheTest {
        var calls = 0
        val f: () -> String = { calls++; "s$calls" }
        assertEquals("s1", cache("c-sync", staleTime = 10.seconds, gcTime = 1.minutes, fetch = f))
        assertEquals("fresh: no call", "s1", cache("c-sync", staleTime = 10.seconds, gcTime = 1.minutes, fetch = f))
        now = 10_000
        assertEquals("stale: runs again", "s2", cache("c-sync", staleTime = 10.seconds, gcTime = 1.minutes, fetch = f))
        advanceTimeBy(59_000)
        assertEquals("s2", cache("c-sync", staleTime = 1.hours, gcTime = 1.minutes, fetch = f))
        advanceTimeBy(2_000); runCurrent()
        assertEquals("gc'd after gcTime without observers", "s3", cache("c-sync", staleTime = 1.hours, gcTime = 1.minutes, fetch = f))
        assertEquals(3, calls)
    }

    @Test fun syncCacheWithGcZeroIsNeverKept() = cacheTest {
        var calls = 0
        val f: () -> Int = { ++calls }
        assertEquals(1, cache("c-sync0", staleTime = 1.hours, gcTime = Duration.ZERO, fetch = f))
        assertEquals(2, cache("c-sync0", staleTime = 1.hours, gcTime = Duration.ZERO, fetch = f))
        assertNull(CacheRegistry.fresh<Int>("c-sync0", Int::class.java.name, 1.hours, Duration.ZERO))
        assertEquals("default staleTime 0: always runs", 3, cache("c-sync0-default", gcTime = 1.minutes, fetch = f))
        assertEquals(4, cache("c-sync0-default", gcTime = 1.minutes, fetch = f))
    }

    @Test fun syncCacheSharesTheSuspendEntry() = cacheTest {
        val q = cache<String>("c-mixed", staleTime = 1.hours) { "suspend" }
        var published = 0
        q.subscribe { published++ }
        q.retain(); advanceUntilIdle()
        var calls = 0
        val f: () -> String = { calls++; "sync" }
        assertEquals("fresh suspend value is reused", "suspend", cache("c-mixed", staleTime = 1.hours, gcTime = 5.minutes, fetch = f))
        assertEquals(0, calls)
        now = 2.hours.inWholeMilliseconds
        val before = published
        assertEquals("sync", cache("c-mixed", staleTime = 1.hours, gcTime = 5.minutes, fetch = f))
        assertEquals("stored into the observed entry and published", "sync", q.snapshot().data)
        assertEquals(before + 1, published)
        advanceTimeBy(10.minutes.inWholeMilliseconds); runCurrent()
        assertEquals("observed: no gc", q, cache<String>("c-mixed") { "x" })
    }

    @Test fun registryFreshMissingAndRemoveGuard() = cacheTest {
        assertNull(CacheRegistry.fresh<String>("c-missing", String::class.java.name, 1.hours, 1.hours))
        val q = cache<String>("c-guard") { "v" }
        assertNull("no data yet", CacheRegistry.fresh<String>("c-guard", String::class.java.name, 1.hours, 1.hours))
        CacheRegistry.remove(identity("c-guard"), CacheEntry(identity("c-guard"), { "other" }, Duration.ZERO, Duration.ZERO))
        assertEquals("only the registered entry itself is removed", q, cache<String>("c-guard") { "v" })
    }

    @Test fun awaitValue() = cacheTest {
        var calls = 0
        val q = cache<String>("c-await", staleTime = 1.hours) { calls++; delay(10); "v$calls" }
        assertEquals("starts a fetch", "v1", q.entry.awaitValue())
        assertEquals("fresh: returns at once", "v1", q.entry.awaitValue())
        assertEquals(1, calls)
        now = 2.hours.inWholeMilliseconds
        q.refetch()
        val joined = cacheScope.async { q.entry.awaitValue() }
        advanceUntilIdle()
        assertEquals("joins the running fetch", "v2", joined.await())
        assertEquals(2, calls)
        val failing = cache<String>("c-await-fail") { delay(1); throw IllegalArgumentException("nope") }
        try {
            failing.entry.awaitValue(); fail("expected error")
        } catch (e: IllegalArgumentException) {
            assertEquals("nope", e.message)
        }
    }

    @Test fun rememberCacheGoesPendingToSuccess_refetch_popEvicts() = cacheTest {
        val owner = FakeOwner()
        var calls = 0
        var result: CacheResult<CProfile>? = null
        lateinit var query: CacheQuery<CProfile>
        host.show(owner) {
            query = cache<CProfile>("c-remember", gcTime = Duration.ZERO, fetch = suspend { calls++; delay(10); CProfile("p$calls") })
            result = rememberCache(query)
        }
        assertTrue(result!!.isPending)
        runCurrent(); host.frame()
        assertTrue(result!!.isFetching)
        advanceUntilIdle(); host.frame()
        assertEquals("p1", result!!.data!!.name)
        query.refetch(); runCurrent(); host.frame()
        assertTrue(result!!.isFetching && result!!.isSuccess)
        advanceUntilIdle(); host.frame()
        assertEquals("p2", result!!.data!!.name)
        host.hide()
        // probe leases keep gcTime 0: every lease updates the entry's times
        assertEquals("hidden: still retained", query, cache<CProfile>("c-remember", gcTime = Duration.ZERO, fetch = suspend { CProfile("?") }))
        owner.pop()
        assertNotEquals("popped with gcTime 0: evicted", query, cache<CProfile>("c-remember", gcTime = Duration.ZERO, fetch = suspend { CProfile("?") }))
    }

    @Test fun rememberCacheEffectNotRestartedEachPass() {
        cacheScope = CoroutineScope(Dispatchers.Unconfined)
        val owner = FakeOwner()
        val tick = mutableStateOf(0)
        var fetches = 0
        host.show(owner) {
            tick.value
            rememberCache(cache<CProfile>("c-eq-pass", staleTime = 60.seconds) { fetches++; CProfile("x") })
        }
        host.act { tick.value++ }
        host.act { tick.value++ }
        assertEquals("one fetch: same entry = same deps, effect not re-run", 1, fetches)
    }

    @Test fun immediateDispatcherFetchCompletesSynchronously() {
        cacheScope = CoroutineScope(Dispatchers.Unconfined)
        val q = cache<String>("c-unconfined", gcTime = 10.milliseconds, fetch = suspend { "now" })
        q.retain()
        q.snapshot().let { assertEquals("now", it.data); assertFalse(it.isFetching) }
        q.release()
    }
}
