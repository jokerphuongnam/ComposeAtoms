package compose.hooks.query

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CacheEntry / CacheStore / AtomStore under real threads (Dispatchers.Default + a thread pool). */
class ConcurrencyTest {
    private fun hammer(threads: Int = 8, rounds: Int = 400, body: (thread: Int, round: Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { t ->
            pool.execute {
                start.await()
                try { repeat(rounds) { r -> body(t, r) } } catch (e: Throwable) { failures += e }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("threads finished", pool.awaitTermination(60, TimeUnit.SECONDS))
        failures.firstOrNull()?.let { throw it }
    }

    private fun awaitTrue(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out: $what")
            Thread.sleep(5)
        }
    }

    @Test fun attachReleaseRefetchSeedSnapshotFromManyThreads() {
        val client = QueryClient(QueryConfig(Dispatchers.Default))
        val fetches = AtomicInteger()
        val query = cache<Int>("hot", gcTime = 1.minutes) { fetches.incrementAndGet(); yield(); 1 }
        val notified = AtomicInteger()
        val unsubscribe = client.cache.obtain<Int>(query.identity).subscribe { notified.incrementAndGet() }
        hammer { t, r ->
            val o = query.observe(client)
            when ((t + r) % 4) {
                0 -> query.refetch(client)
                1 -> client.seedCache("hot", r, freshFor = 1.milliseconds)
                2 -> query.snapshot(client).data
                else -> { val f: () -> Int = { r }; cachedValue("hot", gcTime = 1.minutes, client = client, fetch = f) }
            }
            o.release()
        }
        awaitTrue("settled") { !query.snapshot(client).isFetching }
        query.snapshot(client).let { assertTrue(it.isSuccess); assertFalse(it.isFetching) }
        assertTrue(notified.get() > 0)
        assertTrue(fetches.get() > 0)
        unsubscribe()
        client.clear()
        assertTrue(query.snapshot(client).isPending)
    }

    @Test fun lastReleaseFromManyThreadsEvictsExactlyOnce_noLeakedEntries() {
        val client = QueryClient(QueryConfig(Dispatchers.Default))
        val keys = (0 until 16).map { cache<Int>("k$it", gcTime = Duration.ZERO) { delay(1); it } }
        hammer(rounds = 200) { t, r ->
            val q = keys[(t * 7 + r) % keys.size]
            val o = q.observe(client)
            q.snapshot(client)
            o.release()
        }
        awaitTrue("every entry evicted") { keys.all { it.entryIn(client) == null } }
    }

    @Test fun clearRacingFetchesLeavesNoStuckFetchingState() {
        val client = QueryClient(QueryConfig(Dispatchers.Default))
        val q = cache<Int>("race") { delay(1); 1 }
        hammer(threads = 4, rounds = 200) { t, _ ->
            if (t == 0) client.clear() else q.observe(client).release()
        }
        val o = q.observe(client)
        awaitTrue("fetched after the races") { q.snapshot(client).isSuccess && !q.snapshot(client).isFetching }
        o.release()
        client.clear()
    }

    @Test fun atomStoreWritesAndSubscriptionsFromManyThreads() {
        val client = QueryClient()
        val counter = globalState(client) { 0 }
        val seen = AtomicInteger()
        val unsubscribe = counter.subscribe { seen.incrementAndGet() }
        val lock = Any()
        hammer { _, r ->
            synchronized(lock) { counter.update { it + 1 } } // read-modify-write needs the caller's lock
            val off = counter.subscribe { }
            off()
            if (r % 50 == 0) shareState("s$r", client) { r }.read()
        }
        assertEquals(8 * 400, counter.read())
        assertEquals(8 * 400, seen.get())
        unsubscribe()
    }
}
