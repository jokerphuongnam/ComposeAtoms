package compose.hooks.query

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import compose.hooks.customRemember
import compose.hooks.rememberEffect
import compose.hooks.rememberRef
import kotlin.time.Duration

/**
 * A store for [cache] entries and [globalState] / [shareState] atoms, plus the [QueryConfig] (dispatcher + clock)
 * its cache work uses. Like React Query's `QueryClient`.
 *
 * - [QueryClient.Default] is the process-wide store. Everything that takes no client uses it, so code written
 *   before clients existed keeps working unchanged.
 * - [QueryClientProvider] makes another client current for a part of the UI: [rememberCache] (for a query built
 *   without an explicit client), [rememberGlobalState] and [rememberShareState] resolve [LocalQueryClient].
 *   Entries are per client, so two clients never share data, in-flight fetches or config (no last-caller-wins).
 * - Non-composable calls ([cache], [globalState], [shareState], [seedCache]) take an optional `client`
 *   that defaults to [QueryClient.Default].
 *
 * A client is owned by whoever created it: nothing clears it implicitly. Use [rememberQueryClient] for a client that
 * lives (and is cleared) with a screen's owner.
 *
 * All members are safe to call from any thread.
 */
class QueryClient(config: QueryConfig = QueryConfig()) {
    /** Where this client's cache work runs and which clock it reads. Read at each launch / clock read. */
    @Volatile
    var config: QueryConfig = config

    internal val cache = CacheStore(this)
    internal val atoms = AtomStore()
    private val globals = HashMap<String, Atom<Any?>>()
    internal val shares = ShareRegistry()

    /**
     * Drops every cache entry of this client (logout, account switch, a test's teardown). For each entry the
     * in-flight fetch and the gcTime countdown are cancelled, the entry is reset to Pending and its subscribers are
     * notified once; a [rememberCache] still on screen recomposes, leases a new entry and fetches it again.
     * [CacheQuery] handles stay valid: they name a key + type, not an entry. Atoms are not affected.
     */
    fun clear() {
        cache.clear()
    }

    /**
     * Resets every [globalState] / [globalAtom] of this client. Each value is forgotten, so the next read runs
     * `create` again; subscribers ([rememberGlobalState] on screen) are notified and recompose with the fresh value.
     * Atom handles stay valid (same identity). The type registry is cleared too, so the next `globalState { … }`
     * call for a type registers its `create` anew. Share atoms ([shareState]) are ref-counted and not affected.
     */
    fun resetGlobalStates() {
        synchronized(globals) { globals.clear() }
        atoms.reset()
    }

    /** One atom per [type] in this client (what [globalState] calls with `T::class.java.name`). */
    @Suppress("UNCHECKED_CAST")
    fun <T> globalAtom(type: String, create: () -> T): Atom<T> = synchronized(globals) {
        globals.getOrPut(type) { Atom(atoms, type, create = create) as Atom<Any?> } as Atom<T>
    }

    /** The current state of [key] + [type] (React Query `getQueryState`): Pending when nothing is cached. */
    fun <T> cacheState(key: String, type: String): CacheResult<T> =
        cache.peek<T>(cacheIdentity(key, type))?.snapshot() ?: CacheResult(null, null, false, CacheStatus.Pending)

    /** See [seedCache]. */
    fun <T> seed(key: String, type: String, value: T, freshFor: Duration = Duration.INFINITE) {
        cache.seed(cacheIdentity(key, type), value, freshFor)
    }

    /** Holds [Default]. */
    companion object {
        /** The process-wide client: used by every call that does not name a client, and by [LocalQueryClient]'s default. */
        @JvmStatic
        val Default: QueryClient = QueryClient()
    }
}

/** The client [rememberCache], [rememberGlobalState] and [rememberShareState] use. Default: [QueryClient.Default]. */
val LocalQueryClient: ProvidableCompositionLocal<QueryClient> = staticCompositionLocalOf { QueryClient.Default }

/** Makes [client] the current [LocalQueryClient] for [content]. Does not take ownership of [client]. */
@Composable
fun QueryClientProvider(client: QueryClient, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalQueryClient provides client, content = content)
}

/**
 * A client owned by the caller's hook scope: created once (with [config] from the first pass), kept across
 * recomposition, back navigation and rotation, and cleared ([QueryClient.clear] + [QueryClient.resetGlobalStates])
 * when the owner is cleared (pop). Pass it to [QueryClientProvider] for a per-screen store / dispatcher.
 */
@Composable
fun rememberQueryClient(config: QueryConfig = QueryConfig()): QueryClient = customRemember(name = "rememberQueryClient") {
    val client = rememberRef { QueryClient(config) }.current
    rememberEffect(client) {
        val cleanup = {
            client.clear()
            client.resetGlobalStates()
        }
        cleanup
    }
    client
}

/** [QueryClient.cacheState] for [T]. */
inline fun <reified T> QueryClient.cacheState(key: String): CacheResult<T> = cacheState(key, T::class.java.name)

internal fun cacheIdentity(key: String, type: String): String = "$key\u0000$type"
