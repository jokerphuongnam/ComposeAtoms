package compose.hooks.query.test

import compose.hooks.query.CacheResult
import compose.hooks.query.QueryClient
import compose.hooks.query.QueryClientProvider
import compose.hooks.query.QueryConfig
import compose.hooks.query.cacheState
import compose.hooks.query.seedCache
import compose.hooks.test.HookTestScope
import compose.hooks.test.runHookTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [HookTestScope] with a fresh [client] for this test. Every hook rendered in the test sees [client] as its
 * `LocalQueryClient`; its cache work runs on the test's virtual time (fetches, staleTime and gcTime).
 */
class QueryTestScope internal constructor(
    private val hooks: HookTestScope,
    /** The test's own client (virtual time). */
    val client: QueryClient,
) : HookTestScope by hooks {
    /** Seeds [client]: the value is fresh for [freshFor], so observing it does not fetch. */
    inline fun <reified T> seedCache(key: String, value: T, freshFor: Duration = Duration.INFINITE) {
        client.seedCache(key, value, freshFor)
    }

    /**
     * A fake for one key: seeded forever-fresh in [client], so the code under test never runs its real fetch on its
     * own (an explicit refetch still runs the caller's fetch).
     */
    inline fun <reified T> fakeCache(key: String, value: T) {
        client.seedCache(key, value, Duration.INFINITE)
    }

    /** The current state of [key] + [T] in [client]. */
    inline fun <reified T> cacheState(key: String): CacheResult<T> = client.cacheState(key)
}

/**
 * [runHookTest] plus query isolation:
 * - a new [QueryTestScope.client] on virtual time, provided to every rendered hook ([QueryClientProvider]);
 * - [QueryClient.Default] (used by code that names no client) also runs on virtual time during the test;
 * - teardown (after the hooks' own cleanups): both clients are cleared and their global states reset, and the
 *   Default config is restored, so nothing leaks into the next test.
 */
fun runQueryTest(timeout: Duration = 10.seconds, body: suspend QueryTestScope.() -> Unit) = runHookTest(timeout) {
    val virtual = QueryConfig(mainDispatcher) { currentTime }
    val client = QueryClient(virtual)
    val previousDefault = QueryClient.Default.config
    QueryClient.Default.config = virtual
    addWrapper { content -> QueryClientProvider(client, content) }
    onTeardown {
        client.clear()
        client.resetGlobalStates()
        QueryClient.Default.clear()
        QueryClient.Default.resetGlobalStates()
        QueryClient.Default.config = previousDefault
    }
    QueryTestScope(this, client).body()
}
