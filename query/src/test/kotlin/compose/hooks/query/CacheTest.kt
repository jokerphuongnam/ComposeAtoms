package compose.hooks.query

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One observer of a query, as rememberCache holds it: a lease attached to the entry of [client].
 * Test-only stand-in for the composable (the composable path is in RememberCacheTest).
 */
internal class Observer<T>(private val client: QueryClient, query: CacheQuery<T>) {
    private val lease = CacheLease(query)
    var entry: CacheEntry<T>? = null
        private set

    fun retain(query: CacheQuery<T> = lease.query): Observer<T> {
        val e = client.cache.obtain<T>(query.identity)
        e.update(lease, query)
        e.attach(lease)
        entry = e
        return this
    }

    fun release() {
        entry?.release(lease)
    }
}

internal fun <T> CacheQuery<T>.observe(client: QueryClient) = Observer(client, this).retain()
internal fun <T> CacheQuery<T>.entryIn(client: QueryClient): CacheEntry<T>? = client.cache.peek(identity)

/** cache (suspend + sync), CacheQuery, CacheResult, CacheEntry with per-lease options; one client per test. */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheTest {
    private var now = 0L

    /** a client on the test scheduler: fetches and gc countdowns run in virtual time; staleness reads [now] */
    private fun cacheTest(block: suspend TestScope.(QueryClient) -> Unit) = runTest {
        val client = QueryClient(QueryConfig(StandardTestDispatcher(testScheduler)) { now })
        try { block(client) } finally { client.clear() }
    }

    @Test fun cacheResultFlags_andRefetch() {
        val p = CacheResult<Int>(null, null, false, CacheStatus.Pending)
        assertTrue(p.isPending); assertFalse(p.isSuccess); assertFalse(p.isError)
        p.refetch() // a hand-built result has nothing to refetch
        val s = CacheResult(1, null, true, CacheStatus.Success)
        assertFalse(s.isPending); assertTrue(s.isSuccess); assertFalse(s.isError); assertTrue(s.isFetching)
        val e = CacheResult<Int>(null, IllegalStateException("x"), false, CacheStatus.Error)
        assertFalse(e.isPending); assertFalse(e.isSuccess); assertTrue(e.isError); assertEquals("x", e.error!!.message)
        var refetched = 0
        CacheResult(1, null, false, CacheStatus.Success) { refetched++ }.refetch()
        assertEquals(1, refetched)
    }

    @Test fun handlesAreKeysNotEntries_equalPerKeyTypeAndClient() = cacheTest { client ->
        val a = cache<String>("c-eq") { "x" }
        val b = cache<String>("c-eq") { "y" }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, cache<Int>("c-eq") { 1 })
        assertNotEquals("another client is another query", a, cache<String>("c-eq", client = client) { "x" })
        assertEquals(cache<String>("c-eq", client = client) { "x" }, cache<String>("c-eq", client = client) { "z" })
        assertFalse(a.equals("c-eq"))
        assertEquals("c-eq", a.key)
        assertEquals(Duration.ZERO, a.staleTime)
        assertEquals(5.minutes, a.gcTime)
        assertNull("creating a handle has no side effect", a.entryIn(client))
        assertNull(a.entryIn(QueryClient.Default))
        assertTrue(a.snapshot(client).isPending)
    }

    @Test fun cacheJoinsOneFetchAndSplitsTypes() = cacheTest { client ->
        var calls = 0
        val text = cache<String>("c-fact", staleTime = 60.seconds, gcTime = 60.seconds) { calls += 1; "A" }
        val number = cache<Int>("c-fact", staleTime = 60.seconds, gcTime = 60.seconds) { calls += 1; 1 }
        text.observe(client); number.observe(client)
        advanceUntilIdle()
        assertEquals("A", text.snapshot(client).data)
        assertEquals(1, number.snapshot(client).data)
        assertEquals(2, calls)
        cache<String>("c-fact", staleTime = 60.seconds, gcTime = 60.seconds) { calls += 1; "B" }.observe(client)
        advanceUntilIdle()
        assertEquals("A", text.snapshot(client).data)
        assertEquals(2, calls)
        now = 60_000L
        cache<String>("c-fact") { calls += 1; "C" }.refetch(client)
        advanceUntilIdle()
        assertEquals("refetch runs this handle's fetch", "C", text.snapshot(client).data)
        assertEquals(3, calls)
    }

    @Test fun snapshotGoesPendingFetchingSuccess_subscribeUnsubscribe() = cacheTest { client ->
        val q = cache<String>("c-transitions") { delay(100); "v" }
        val entry = client.cache.obtain<String>(q.identity)
        entry.snapshot().let { assertTrue(it.isPending); assertFalse(it.isFetching); assertNull(it.data) }
        var published = 0
        val unsubscribe = entry.subscribe { published++ }
        q.observe(client)
        q.snapshot(client).let { assertTrue(it.isPending); assertTrue(it.isFetching) }
        assertEquals(1, published)
        advanceUntilIdle()
        q.snapshot(client).let { assertTrue(it.isSuccess); assertFalse(it.isFetching); assertEquals("v", it.data); assertNull(it.error) }
        assertEquals(2, published)
        unsubscribe()
        q.refetch(client); advanceUntilIdle()
        assertEquals("unsubscribed listener is not called", 2, published)
    }

    @Test fun concurrentObserversShareOneFetch_doubleAttachIsIgnored() = cacheTest { client ->
        var calls = 0
        val a = cache<String>("c-dedupe") { calls++; delay(50); "v" }.observe(client)
        runCurrent()
        a.retain() // the same lease again: no second fetch, no second lease
        cache<String>("c-dedupe") { calls++; delay(50); "w" }.observe(client)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals("v", a.entry!!.snapshot().data)
    }

    @Test fun staleDataIsRefetchedOnAttach_freshIsNot() = cacheTest { client ->
        var calls = 0
        val q = cache<String>("c-stale", staleTime = 10.seconds) { calls++; "v$calls" }
        val o = q.observe(client); advanceUntilIdle()
        o.release()
        now = 5_000; o.retain(); runCurrent()
        assertEquals("fresh: no fetch", 1, calls)
        o.release()
        now = 20_000; o.retain()
        q.snapshot(client).let { assertTrue("keeps showing stale data while refetching", it.isSuccess && it.isFetching); assertEquals("v1", it.data) }
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("v2", q.snapshot(client).data)
    }

    /** refetch() cancels the in-flight fetch; the cancelled one must not clear the new one's job / isFetching. */
    @Test fun refetchCancelsInFlightFetchAndKeepsOwningTheNewOne() = cacheTest { client ->
        var calls = 0
        val started = ArrayList<Int>()
        val q = cache<String>("c-force") { val n = ++calls; started += n; delay(1_000); "v$n" }
        q.observe(client); runCurrent()
        advanceTimeBy(100)
        q.refetch(client)
        runCurrent()
        assertTrue("second fetch is still running", q.snapshot(client).isFetching)
        cache<String>("c-force") { val n = ++calls; started += n; delay(1_000); "v$n" }.observe(client)
        runCurrent()
        assertEquals("joined the running refetch", listOf(1, 2), started)
        advanceUntilIdle()
        q.snapshot(client).let { assertEquals("v2", it.data); assertFalse(it.isFetching) }
    }

    @Test fun errorStatusThenRecoveryOnRefetch_keepsLastData() = cacheTest { client ->
        var fail = true
        val q = cache<String>("c-error") { if (fail) throw IllegalStateException("boom") else "ok" }
        q.observe(client); advanceUntilIdle()
        q.snapshot(client).let { assertTrue(it.isError); assertEquals("boom", it.error!!.message); assertNull(it.data); assertFalse(it.isFetching) }
        fail = false
        q.refetch(client); advanceUntilIdle()
        q.snapshot(client).let { assertTrue(it.isSuccess); assertNull(it.error); assertEquals("ok", it.data) }
        fail = true
        q.refetch(client); advanceUntilIdle()
        q.snapshot(client).let { assertTrue(it.isError); assertEquals("data of the last success is kept", "ok", it.data) }
    }

    @Test fun cancellationIsNotAnError() = cacheTest { client ->
        val q = cache<String>("c-cancel") { throw CancellationException("stop") }
        q.observe(client); advanceUntilIdle()
        q.snapshot(client).let { assertTrue(it.isPending); assertNull(it.error); assertFalse(it.isFetching) }
    }

    @Test fun fetchWithoutBodyOrPreviousFetchDoesNothing() = cacheTest { client ->
        val entry = client.cache.obtain<String>("never")
        entry.fetch(force = true)
        runCurrent()
        assertFalse(entry.snapshot().isFetching)
        val q = cache<String>("c-last") { "last" }
        val o = q.observe(client); advanceUntilIdle()
        o.entry!!.fetch(force = true) // no body: the leased fetch
        advanceUntilIdle()
        assertEquals("last", q.snapshot(client).data)
    }

    @Test fun gcTimeZeroEvictsOnLastRelease() = cacheTest { client ->
        val q = cache<String>("c-gc0", gcTime = Duration.ZERO) { "v" }
        val o = q.observe(client); advanceUntilIdle()
        val entry = o.entry
        o.release()
        assertNull("evicted", q.entryIn(client))
        o.release() // a second release is ignored
        assertNotSame(entry, client.cache.obtain<String>(q.identity))
    }

    @Test fun gcTimeDelaysEviction_attachCancelsCountdown_otherObserversKeepEntry() = cacheTest { client ->
        val q = cache<String>("c-gc", gcTime = 1.seconds) { "v" }
        val a = q.observe(client)
        val b = q.observe(client)
        advanceUntilIdle()
        val entry = a.entry
        a.release()
        advanceTimeBy(5_000)
        assertSame("another observer keeps it", entry, q.entryIn(client))
        b.release()
        advanceTimeBy(500)
        a.retain() // comes back before gcTime: countdown cancelled
        advanceTimeBy(5_000)
        assertSame(entry, q.entryIn(client))
        a.release()
        advanceTimeBy(999)
        assertSame(entry, q.entryIn(client))
        advanceTimeBy(2); runCurrent()
        assertNull(q.entryIn(client))
        entry!!.attach(CacheLease(q)) // an evicted entry is inert: attaching to it does nothing
        assertNull(q.entryIn(client))
        a.retain() // the handle still works: it leases a new entry
        assertNotSame(entry, q.entryIn(client))
    }

    @Test fun evictDuringFetchCancelsIt() = cacheTest { client ->
        var completed = false
        val q = cache<String>("c-evict-fetch", gcTime = Duration.ZERO) { delay(1_000); completed = true; "v" }
        val o = q.observe(client); runCurrent()
        assertTrue(q.snapshot(client).isFetching)
        o.release()
        advanceUntilIdle()
        assertFalse(completed)
        o.entry!!.snapshot().let { assertNull(it.data) }
        assertNull(q.entryIn(client))
    }

    // ---- per-lease options (React Query semantics) ----

    @Test fun staleTimeIsTheMinimumOverLiveLeases() = cacheTest { client ->
        var calls = 0
        val fetch: suspend () -> String = { calls++; "v$calls" }
        val long = cache("c-min", staleTime = 1.hours, fetch = fetch).observe(client)
        advanceUntilIdle()
        val short = cache("c-min", staleTime = 10.seconds, fetch = fetch).observe(client)
        runCurrent()
        assertEquals("fresh for both", 1, calls)
        now = 20_000
        cache("c-min", staleTime = 1.hours, fetch = fetch).observe(client); runCurrent()
        assertEquals("stale for the 10s lease: the minimum applies", 2, calls)
        advanceUntilIdle()
        short.release()
        now = 40_000
        cache("c-min", staleTime = 1.hours, fetch = fetch).observe(client); runCurrent()
        assertEquals("the 10s lease left: only the 1h leases count", 2, calls)
        long.release()
    }

    @Test fun updatedOptionsOfALiveLeaseApply() = cacheTest { client ->
        var calls = 0
        val fetch: suspend () -> String = { calls++; "v$calls" }
        val o = cache("c-upd", staleTime = 1.hours, fetch = fetch).observe(client)
        advanceUntilIdle()
        val entry = o.entry!!
        o.retain(cache("c-upd", staleTime = 1.seconds, fetch = fetch)) // the same lease with new options
        now = 2_000
        cache("c-upd", staleTime = 1.hours, fetch = fetch).observe(client); runCurrent()
        assertEquals("the live lease now has 1s", 2, calls)
        assertSame(entry, o.entry)
    }

    @Test fun gcTimeIsTheMaximumEverLeased() = cacheTest { client ->
        val short = cache<String>("c-max", gcTime = 1.seconds) { "v" }.observe(client)
        val long = cache<String>("c-max", gcTime = 10.seconds) { "v" }.observe(client)
        advanceUntilIdle()
        long.release() // the long lease leaves first: its gcTime still counts (monotonic max)
        short.release()
        advanceTimeBy(5_000)
        assertTrue("kept for the largest gcTime", cache<String>("c-max") { "?" }.entryIn(client) != null)
        advanceTimeBy(5_001)
        assertNull(cache<String>("c-max") { "?" }.entryIn(client))
    }

    @Test fun theLatestLeasedFetchRuns_aDetachedLeaseUpdateIsIgnored() = cacheTest { client ->
        val first = cache<String>("c-body") { "a" }.observe(client)
        advanceUntilIdle()
        cache<String>("c-body") { "b" }.observe(client)
        advanceUntilIdle()
        first.entry!!.fetch(force = true); advanceUntilIdle()
        assertEquals("b", first.entry!!.snapshot().data)
        // a lease that is not attached does not change the entry's fetch
        client.cache.obtain<String>(cacheIdentity("c-body", String::class.java.name))
            .update(CacheLease(cache("c-body") { "c" }), cache("c-body") { "c" })
        first.entry!!.fetch(force = true); advanceUntilIdle()
        assertEquals("b", first.entry!!.snapshot().data)
    }

    // ---- cachedValue (sync) ----

    @Test fun trailingLambdaFormsResolveWithoutAmbiguity() = cacheTest { client ->
        // cache(key, gcTime = X) { } is a query; cachedValue(key, ...) { } is the synchronous value
        val q: CacheQuery<String> = cache("c-trail", gcTime = 1.minutes) { "q" }
        assertEquals(1.minutes, q.gcTime)
        assertEquals(7, cachedValue("c-trail-sync", client = client) { 7 })
        assertEquals("fresh for this call's staleTime", 7, cachedValue("c-trail-sync", staleTime = 1.hours, client = client) { 8 })
    }

    @Test fun syncCacheFreshStaleAndGc() = cacheTest { client ->
        var calls = 0
        val f: () -> String = { calls++; "s$calls" }
        assertEquals("s1", cachedValue("c-sync", staleTime = 10.seconds, gcTime = 1.minutes, client = client, fetch = f))
        assertEquals("fresh: no call", "s1", cachedValue("c-sync", staleTime = 10.seconds, gcTime = 1.minutes, client = client, fetch = f))
        now = 10_000
        assertEquals("stale: runs again", "s2", cachedValue("c-sync", staleTime = 10.seconds, gcTime = 1.minutes, client = client, fetch = f))
        advanceTimeBy(59_000)
        assertEquals("s2", cachedValue("c-sync", staleTime = 1.hours, gcTime = 1.minutes, client = client, fetch = f))
        advanceTimeBy(2_000); runCurrent()
        assertEquals("gc'd after gcTime without observers", "s3", cachedValue("c-sync", staleTime = 1.hours, gcTime = 1.minutes, client = client, fetch = f))
        assertEquals(3, calls)
    }

    @Test fun syncCacheWithGcZeroIsNeverKept_defaultStaleTimeAlwaysRuns() = cacheTest { client ->
        var calls = 0
        val f: () -> Int = { ++calls }
        assertEquals(1, cachedValue("c-sync0", staleTime = 1.hours, gcTime = Duration.ZERO, client = client, fetch = f))
        assertEquals(2, cachedValue("c-sync0", staleTime = 1.hours, gcTime = Duration.ZERO, client = client, fetch = f))
        assertNull(client.cache.fresh<Int>(cacheIdentity("c-sync0", Int::class.java.name), 1.hours, Duration.ZERO))
        assertEquals(3, cachedValue("c-sync0-default", gcTime = 1.minutes, client = client, fetch = f))
        assertEquals(4, cachedValue("c-sync0-default", gcTime = 1.minutes, client = client, fetch = f))
    }

    @Test fun syncCacheSharesTheSuspendEntry_observedIsNotCollected() = cacheTest { client ->
        val q = cache<String>("c-mixed", staleTime = 1.hours) { "suspend" }
        val o = q.observe(client)
        var published = 0
        o.entry!!.subscribe { published++ }
        advanceUntilIdle()
        var calls = 0
        val f: () -> String = { calls++; "sync" }
        assertEquals("fresh suspend value is reused", "suspend", cachedValue("c-mixed", staleTime = 1.hours, gcTime = 5.minutes, client = client, fetch = f))
        assertEquals(0, calls)
        now = 2.hours.inWholeMilliseconds
        val before = published
        assertEquals("sync", cachedValue("c-mixed", staleTime = 1.hours, gcTime = 5.minutes, client = client, fetch = f))
        assertEquals("stored into the observed entry and published", "sync", q.snapshot(client).data)
        assertEquals(before + 1, published)
        advanceTimeBy(10.minutes.inWholeMilliseconds); runCurrent()
        assertSame("observed: no gc", o.entry, q.entryIn(client))
    }

    @Test fun storeOnADetachedEntryIsIgnored() = cacheTest { client ->
        val q = cache<String>("c-late", gcTime = Duration.ZERO) { "v" }
        val o = q.observe(client); advanceUntilIdle()
        val entry = o.entry!!
        o.release() // evicted
        var notified = 0
        entry.subscribe { notified++ }
        entry.remember("late", 1.minutes)
        entry.seed("late", Duration.INFINITE)
        assertEquals("an evicted entry keeps its last state, unchanged", "v", entry.snapshot().data)
        assertEquals(0, notified)
        assertNull(q.entryIn(client))
    }

    @Test fun immediateDispatcherFetchCompletesSynchronously() {
        val client = QueryClient(QueryConfig(Dispatchers.Unconfined) { now })
        val q = cache<String>("c-unconfined", gcTime = 10.milliseconds) { "now" }
        val o = q.observe(client)
        q.snapshot(client).let { assertEquals("now", it.data); assertFalse(it.isFetching) }
        o.release()
        client.clear()
    }

    /** A fetch whose scope is already cancelled never runs; it must not leave isFetching stuck. */
    @Test fun aFetchThatNeverRunsDoesNotStickInFetching() {
        val dead = Job().also { it.cancel() }
        val client = QueryClient(QueryConfig(dead + Dispatchers.Unconfined) { now })
        val q = cache<String>("c-dead") { "never" }
        q.observe(client)
        q.snapshot(client).let { assertFalse(it.isFetching); assertTrue(it.isPending) }
    }
}
