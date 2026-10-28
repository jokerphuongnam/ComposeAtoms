package compose.hooks.query.test

import compose.hooks.query.LocalQueryClient
import compose.hooks.query.QueryClient
import compose.hooks.query.cache
import compose.hooks.query.cacheState
import compose.hooks.query.globalState
import compose.hooks.query.rememberCache
import compose.hooks.query.seedCache
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** query-test itself: the per-test client, virtual time, seed / fake helpers, isolation and teardown. */
class QueryTestTest {
    @Test fun everyHookSeesTheTestClient_notDefault() = runQueryTest {
        assertNotSame(QueryClient.Default, client)
        assertSame(client, renderHook { LocalQueryClient.current }.current)
    }

    @Test fun fetchesStaleTimeAndGcRunOnVirtualTime() = runQueryTest {
        var calls = 0
        val entry = backStackEntry()
        val result = renderHook(entry) {
            rememberCache(cache("user", staleTime = 30.seconds, gcTime = 1.minutes) { calls++; delay(1_000); "u$calls" })
        }
        assertTrue(result.current.isFetching)
        advanceTimeBy(1_000)
        assertEquals("u1", result.current.data)
        assertEquals(1_000L, currentTime)
        renderHook { rememberCache(cache("user", staleTime = 30.seconds) { calls++; "again" }) }
        assertEquals("fresh on virtual time", 1, calls)
        entry.pop()
        advanceTimeBy(30.seconds)
        renderHook { rememberCache(cache("user", staleTime = 30.seconds) { calls++; "again" }) }.also { advanceUntilIdle() }
        assertEquals("stale after 30s of virtual time", 2, calls)
    }

    @Test fun seedCache_fakeCache_cacheState() = runQueryTest {
        seedCache("seeded", 1, freshFor = 10.seconds)
        fakeCache("faked", "fake")
        assertEquals(1, cacheState<Int>("seeded").data)
        assertEquals("fake", cacheState<String>("faked").data)
        assertTrue("another type is another entry", cacheState<String>("seeded").isPending)
        var fetched = 0
        val faked = renderHook { rememberCache(cache("faked") { fetched++; "real" }) }
        advanceTimeBy(365L * 24 * 3600 * 1000)
        renderHook { rememberCache(cache("faked") { fetched++; "real" }) }
        assertEquals("a fake is never stale", 0, fetched)
        faked.act { faked.current.refetch() }
        advanceUntilIdle()
        assertEquals("an explicit refetch still runs", "real", faked.current.data)
        assertTrue(QueryClient.Default.cacheState<Int>("seeded").isPending)
    }

    @Test fun defaultClientAlsoRunsOnVirtualTimeDuringTheTest() = runQueryTest {
        assertSame(mainDispatcher, QueryClient.Default.config.context)
        advanceTimeBy(5_000)
        assertEquals(5_000L, QueryClient.Default.config.nowMillis())
        assertEquals(5_000L, client.config.nowMillis())
    }

    @Test fun teardownClearsBothClientsAndRestoresTheDefaultConfig() {
        val before = QueryClient.Default.config
        lateinit var testClient: QueryClient
        runQueryTest {
            testClient = client
            seedCache("mine", 1)
            QueryClient.Default.seedCache("default", 2)
            globalState(client) { 0 }.write(5)
            globalState { "d" }.write("changed")
        }
        assertTrue(testClient.cacheState<Int>("mine").isPending)
        assertEquals(0, globalState(testClient) { 0 }.read())
        assertTrue(QueryClient.Default.cacheState<Int>("default").isPending)
        assertEquals("d", globalState { "d" }.read())
        assertSame(before, QueryClient.Default.config)
    }

    @Test fun testsAreIsolatedFromEachOther() {
        runQueryTest { seedCache("leak", 1) }
        runQueryTest { assertFalse(cacheState<Int>("leak").isSuccess) }
    }
}
