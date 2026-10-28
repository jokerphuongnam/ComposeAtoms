package compose.hooks.query

import compose.hooks.query.test.runQueryTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Profile(val name: String)

/** rememberCache through hooks-test / query-test (dogfooding): composition, owners, the test client. */
class RememberCacheTest {
    @Test fun pendingFetchingSuccess_refetchFromTheResult_popEvicts() = runQueryTest {
        var calls = 0
        val entry = backStackEntry()
        val result = renderHook(owner = entry) {
            rememberCache(cache("p", gcTime = Duration.ZERO) { calls++; delay(10); Profile("p$calls") })
        }
        result.current.let { assertTrue(it.isPending); assertTrue(it.isFetching) }
        advanceUntilIdle()
        assertEquals(Profile("p1"), result.current.data)
        result.act { result.current.refetch() }
        result.current.let { assertTrue(it.isFetching && it.isSuccess) }
        advanceUntilIdle()
        assertEquals(Profile("p2"), result.current.data)
        result.hide()
        assertEquals("hidden: still leased", Profile("p2"), cacheState<Profile>("p").data)
        entry.pop()
        assertTrue("popped with gcTime 0: evicted", cacheState<Profile>("p").isPending)
        assertEquals(0, entry.hookScopeCount)
    }

    @Test fun recompositionDoesNotRestartTheEffect_newOptionsApply() = runQueryTest {
        var fetches = 0
        val result = renderHookWithProps(1.hours) { stale ->
            rememberCache(cache("eq-pass", staleTime = stale) { fetches++; Profile("x") })
        }
        advanceUntilIdle()
        result.rerender(2.hours)
        result.rerender(1.seconds)
        assertEquals("one fetch: same entry, effect not re-run", 1, fetches)
        advanceTimeBy(2.seconds)
        renderHook { rememberCache(cache("eq-pass", staleTime = 1.hours) { fetches++; Profile("y") }) }
        assertEquals("the first observer's new staleTime (1s) applies", 2, fetches)
    }

    @Test fun fakeCacheServesTheHookWithoutFetching() = runQueryTest {
        fakeCache("me", Profile("fake"))
        var fetched = false
        val result = renderHook { rememberCache(cache("me") { fetched = true; Profile("real") }) }
        advanceUntilIdle()
        assertEquals(Profile("fake"), result.current.data)
        assertFalse(fetched)
    }

    @Test fun clearWhileOnScreenReleasesAndRefetches() = runQueryTest {
        var calls = 0
        val result = renderHook { rememberCache(cache("live") { calls++; Profile("u$calls") }) }
        advanceUntilIdle()
        assertEquals(Profile("u1"), result.current.data)
        client.clear()
        advanceUntilIdle()
        assertEquals("the hook leased a new entry and fetched it", Profile("u2"), result.current.data)
        assertTrue(result.history.any { it.isPending })
    }

    @Test fun aQueryNamingAClientIgnoresLocalQueryClient() = runQueryTest {
        val other = QueryClient(client.config)
        val result = renderHook { rememberCache(cache("named", client = other) { Profile("other") }) }
        advanceUntilIdle()
        assertEquals(Profile("other"), result.current.data)
        assertTrue(cacheState<Profile>("named").isPending)
        assertEquals(Profile("other"), other.cacheState<Profile>("named").data)
        onTeardown { other.clear() }
    }

    @Test fun errorsAreResults_notTestFailures() = runQueryTest {
        val result = renderHook { rememberCache(cache<Profile>("boom") { error("down") }) }
        advanceUntilIdle()
        assertEquals("down", result.current.error!!.message)
        assertNull(result.current.data)
        assertTrue(takeErrors().isEmpty())
    }
}
