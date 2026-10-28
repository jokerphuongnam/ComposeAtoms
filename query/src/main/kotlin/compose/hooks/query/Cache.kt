package compose.hooks.query

import androidx.compose.runtime.Composable
import compose.hooks.customRemember
import compose.hooks.rememberEffect
import compose.hooks.rememberMemo
import compose.hooks.rememberState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where an entry is: no data or error yet ([Pending]), last fetch succeeded or was seeded ([Success]), last fetch failed ([Error]). */
enum class CacheStatus {
    /** No data and no error yet (first fetch not finished). */
    Pending,

    /** The last fetch succeeded, or the entry was seeded. */
    Success,

    /** The last fetch failed ([CacheResult.error]); earlier data, if any, is kept in [CacheResult.data]. */
    Error,
}

/** A snapshot of one cache entry, as [rememberCache] and [cacheState] return it. */
class CacheResult<T> internal constructor(
    /** The latest successful value (kept while refetching and after a failed refetch); null while pending. */
    val data: T?,
    /** The error of the latest fetch when it failed; null otherwise. */
    val error: Throwable?,
    /** True while a fetch is in flight (also during a background refetch with [data] present). */
    val isFetching: Boolean,
    /** See [CacheStatus]. */
    val status: CacheStatus,
    private val onRefetch: () -> Unit,
) {
    /** A result built by hand (previews, fakes); its [refetch] does nothing. */
    constructor(data: T?, error: Throwable?, isFetching: Boolean, status: CacheStatus) :
        this(data, error, isFetching, status, {})

    /** `status == Pending`. */
    val isPending: Boolean get() = status == CacheStatus.Pending
    /** `status == Success`. */
    val isSuccess: Boolean get() = status == CacheStatus.Success
    /** `status == Error`. */
    val isError: Boolean get() = status == CacheStatus.Error

    /** Fetches again in the client this result came from (a result built by hand does nothing). */
    fun refetch() {
        onRefetch()
    }
}

/**
 * A query: the string [key] plus [T] is the identity, the rest are this caller's options (its *lease*).
 * The closure is not compared. Same key and same type share one entry and one in-flight fetch per [QueryClient].
 * Same key and another type are another cache.
 *
 * A handle names a key, not an entry: it is cheap, has no side effect when created, and stays valid after the entry
 * was garbage collected or the client cleared. Without an explicit `client` it means [QueryClient.Default] outside
 * composition and [LocalQueryClient] in [rememberCache].
 *
 * Several leases on one entry (React Query semantics): the entry is stale when it is older than the **smallest**
 * staleTime among its live leases ([rememberCache] callers), and after the last lease is released it is kept for
 * the **largest** gcTime any lease of this entry has used. The fetch that runs is the most recently leased one.
 */
class CacheQuery<T> internal constructor(
    /** The cache key (with [T], the identity of the entry). */
    val key: String,
    internal val type: String,
    /** How long data stays fresh for this lease (the entry uses the smallest among live leases). */
    val staleTime: Duration,
    /** How long the entry is kept after its last lease is released (the entry uses the largest ever leased). */
    val gcTime: Duration,
    internal val client: QueryClient?,
    internal val fetch: suspend () -> T,
) {
    internal val identity: String = cacheIdentity(key, type)

    internal fun resolve(): QueryClient = client ?: QueryClient.Default

    /** The current state of this key in [client]: Pending when nothing is cached. */
    fun snapshot(client: QueryClient = resolve()): CacheResult<T> =
        client.cache.peek<T>(identity)?.snapshot() ?: CacheResult(null, null, false, CacheStatus.Pending)

    /** Fetches now in [client] with this query's fetch, replacing (cancelling) one already in flight. */
    fun refetch(client: QueryClient = resolve()) {
        client.cache.obtain<T>(identity).fetch(force = true, body = fetch)
    }

    // two handles to the same key + type in the same client are the same query (stable effect deps)
    /** Same key, same type and same client (options and fetch are not compared). */
    override fun equals(other: Any?): Boolean =
        other is CacheQuery<*> && other.identity == identity && other.client === client

    /** Consistent with [equals]. */
    override fun hashCode(): Int = identity.hashCode() * 31 + System.identityHashCode(client)
}

/**
 * A suspend query (see [CacheQuery]); observe it with [rememberCache]. Creating it has no side effect.
 *
 * ```
 * val profile = cache("profile", staleTime = 1.minutes, gcTime = 10.minutes) { api.profile() }
 * ```
 * For a synchronous value use [cachedValue].
 */
inline fun <reified T> cache(
    key: String,
    staleTime: Duration = Duration.ZERO,
    gcTime: Duration = 5.minutes,
    client: QueryClient? = null,
    noinline fetch: suspend () -> T,
): CacheQuery<T> = cacheQuery(key, T::class.java.name, staleTime, gcTime, client, fetch)

@PublishedApi
internal fun <T> cacheQuery(
    key: String,
    type: String,
    staleTime: Duration,
    gcTime: Duration,
    client: QueryClient?,
    fetch: suspend () -> T,
): CacheQuery<T> = CacheQuery(key, type, staleTime, gcTime, client, fetch)

/**
 * The synchronous form of [cache]: runs [fetch] on the caller and returns the value, unless a value fresh for this
 * call's [staleTime] (or a fresh seed) is cached under [key] + [T]. The value is stored in the same entry a
 * [cache] query of that key and type uses, so observers of the entry see it. Kept for [gcTime] when nobody observes
 * it. [client] null = [QueryClient.Default] (this is not a composable).
 *
 * ```
 * val config = cachedValue("config", staleTime = 1.hours) { parseConfig(assets) }
 * ```
 */
inline fun <reified T> cachedValue(
    key: String,
    staleTime: Duration = Duration.ZERO,
    gcTime: Duration = 5.minutes,
    client: QueryClient? = null,
    noinline fetch: () -> T,
): T {
    val target = client ?: QueryClient.Default
    syncFresh<T>(target, key, T::class.java.name, staleTime, gcTime)?.let { return it }
    val value = fetch()
    syncStore(target, key, T::class.java.name, value, gcTime)
    return value
}

@PublishedApi
internal fun <T> syncFresh(client: QueryClient, key: String, type: String, staleTime: Duration, gcTime: Duration): T? =
    client.cache.fresh(cacheIdentity(key, type), staleTime, gcTime)

@PublishedApi
internal fun <T> syncStore(client: QueryClient, key: String, type: String, value: T, gcTime: Duration) {
    client.cache.store(cacheIdentity(key, type), value, gcTime)
}

/**
 * Puts [value] into [client]'s cache under [key] + [T] as if it had just been fetched, and keeps it fresh for
 * [freshFor] regardless of the `staleTime` that leases pass. While fresh, observing the entry does not fetch; once
 * [freshFor] has passed the entry follows its leases' staleTime again. A real fetch (refetch, or a retain after the
 * seed went stale) replaces the seed. Seeding cancels a fetch already in flight and a pending gcTime countdown.
 * A seeded entry that nobody observes is not garbage collected until it has been observed and released (its
 * gcTime then applies as usual) or the client is cleared.
 *
 * ```
 * seedCache("profile/42", Profile("Ada"))            // test fixture, offline snapshot, deep-link payload…
 * rememberCache(cache("profile/42") { api.load(42) }) // Success("Ada") without fetching
 * ```
 */
inline fun <reified T> seedCache(key: String, value: T, freshFor: Duration = Duration.INFINITE) {
    QueryClient.Default.seed(key, T::class.java.name, value, freshFor)
}

/** [seedCache] into this client. */
inline fun <reified T> QueryClient.seedCache(key: String, value: T, freshFor: Duration = Duration.INFINITE) {
    seed(key, T::class.java.name, value, freshFor)
}

/**
 * Subscribes the caller's hook scope to [query] in its client ([LocalQueryClient] unless the query names one).
 * The caller holds a lease with the query's staleTime / gcTime / fetch (updated on every pass, so changed options
 * apply without restarting). Built on core hooks: the observer is a [rememberEffect] whose cleanup unsubscribes and
 * releases, so the lease is held while the caller's owner lives (also while the screen is on the back stack) and the
 * gcTime countdown starts when the owner is cleared (pop). After [QueryClient.clear] the hook leases a new entry.
 */
@Composable
fun <T> rememberCache(query: CacheQuery<T>): CacheResult<T> {
    val client = query.client ?: LocalQueryClient.current
    return customRemember(name = "rememberCache") {
        val tick = rememberState { 0 }
        tick.value // read: a publish (or a clear) recomposes this hook
        val entry = client.cache.obtain<T>(query.identity)
        val lease = rememberMemo(entry) { CacheLease(query) }
        entry.update(lease, query)
        rememberEffect(entry) {
            // subscribe before attaching: a fetch that completes synchronously (immediate dispatcher) must not be missed
            val unsubscribe = entry.subscribe { tick.value++ }
            entry.attach(lease)
            val cleanup = {
                unsubscribe()
                entry.release(lease)
            }
            cleanup
        }
        entry.snapshot { entry.fetch(force = true, body = lease.query.fetch) }
    }
}

/**
 * The cache entries of [QueryClient.Default] (kept for code written before [QueryClient]; prefer the client API).
 */
object CacheRegistry {
    /** Same as [cache]: a handle to [key] + [type] (no client named); no side effect until it is observed or refetched. */
    fun <T> lease(
        key: String,
        type: String,
        staleTime: Duration,
        gcTime: Duration,
        fetch: suspend () -> T,
    ): CacheQuery<T> = CacheQuery(key, type, staleTime, gcTime, null, fetch)

    /** The value of [key] + [type] in [QueryClient.Default] if it is fresh for [staleTime], else null. */
    fun <T> fresh(key: String, type: String, staleTime: Duration, gcTime: Duration): T? =
        QueryClient.Default.cache.fresh(cacheIdentity(key, type), staleTime, gcTime)

    /** Stores [value] for [key] + [type] in [QueryClient.Default] as if just fetched ([staleTime] is unused: freshness is decided per read). */
    @Suppress("UNUSED_PARAMETER") // staleTime: kept for source compatibility; freshness is decided per read
    fun <T> store(key: String, type: String, value: T, staleTime: Duration, gcTime: Duration) {
        QueryClient.Default.cache.store(cacheIdentity(key, type), value, gcTime)
    }

    /** See [seedCache]. */
    fun <T> seed(key: String, type: String, value: T, freshFor: Duration) {
        QueryClient.Default.seed(key, type, value, freshFor)
    }

    /** [QueryClient.clear] of [QueryClient.Default]. */
    fun clear() {
        QueryClient.Default.clear()
    }
}

/** One observer's options; held by [rememberCache] for the observer's lifetime. */
internal class CacheLease<T>(@Volatile var query: CacheQuery<T>)

/**
 * The entries of one client. Every entry's state is guarded by [lock] (one lock per client: no lock ordering between
 * registry and entries). Fetch bodies and listeners run outside the lock.
 */
internal class CacheStore(private val client: QueryClient) {
    val lock = Any()
    private val entries = HashMap<String, CacheEntry<*>>()

    val config: QueryConfig get() = client.config

    @Suppress("UNCHECKED_CAST")
    fun <T> peek(identity: String): CacheEntry<T>? = synchronized(lock) { entries[identity] as CacheEntry<T>? }

    @Suppress("UNCHECKED_CAST")
    fun <T> obtain(identity: String): CacheEntry<T> = synchronized(lock) {
        entries.getOrPut(identity) { CacheEntry<T>(this, identity) } as CacheEntry<T>
    }

    fun <T> fresh(identity: String, staleTime: Duration, gcTime: Duration): T? = synchronized(lock) {
        peek<T>(identity)?.freshLocked(staleTime, gcTime)
    }

    fun <T> store(identity: String, value: T, gcTime: Duration) {
        obtain<T>(identity).remember(value, gcTime)
    }

    fun <T> seed(identity: String, value: T, freshFor: Duration) {
        require(!freshFor.isNegative()) { "freshFor must not be negative: $freshFor" }
        obtain<T>(identity).seed(value, freshFor)
    }

    fun clear() {
        val dropped = synchronized(lock) { entries.values.toList().also { entries.clear() } }
        dropped.forEach { it.detach() }
    }

    fun removeLocked(identity: String, entry: CacheEntry<*>) {
        if (entries[identity] === entry) entries.remove(identity)
    }
}

internal class CacheEntry<T>(
    private val store: CacheStore,
    private val identity: String,
) {
    private val lock = store.lock

    // all fields below are guarded by [lock]
    private var data: T? = null
    private var error: Throwable? = null
    private var isFetching = false
    private var status = CacheStatus.Pending
    private var updatedAt: Long? = null
    private val leases = LinkedHashSet<CacheLease<T>>()
    // max gcTime over every lease / sync store of this entry; null until one used it (a seed alone never expires)
    private var gcTime: Duration? = null
    private var lastFetch: (suspend () -> T)? = null
    private var job: Job? = null
    private var collect: Job? = null
    // non-null while a seeded value is fresh for this long (independent of the leases' staleTime)
    private var seedFreshFor: Duration? = null
    private var detached = false
    private val listeners = LinkedHashSet<() -> Unit>()

    fun snapshot(refetch: () -> Unit = {}): CacheResult<T> =
        synchronized(lock) { CacheResult(data, error, isFetching, status, refetch) }

    fun subscribe(listener: () -> Unit): () -> Unit {
        synchronized(lock) { listeners.add(listener) }
        return { synchronized(lock) { listeners.remove(listener) } }
    }

    /** New options of [lease] (every [rememberCache] pass). Does not fetch by itself. */
    fun update(lease: CacheLease<T>, query: CacheQuery<T>) {
        synchronized(lock) {
            lease.query = query
            if (lease in leases) noteLocked(query)
        }
    }

    /** A new observer: cancels the gc countdown, fetches when there is no data or it is stale. */
    fun attach(lease: CacheLease<T>) {
        val start = synchronized(lock) {
            if (detached || !leases.add(lease)) return
            noteLocked(lease.query)
            collect?.cancel()
            collect = null
            if (data == null || isStaleLocked(null)) startFetchLocked(force = false, body = lease.query.fetch) else null
        }
        launch(start)
    }

    /** An observer left: after the last one the entry is kept for its gcTime. */
    fun release(lease: CacheLease<T>) {
        synchronized(lock) {
            if (!leases.remove(lease) || detached || leases.isNotEmpty()) return
            scheduleGcLocked()
        }
    }

    fun fetch(force: Boolean, body: (suspend () -> T)? = null) {
        val start = synchronized(lock) {
            val fetch = body ?: lastFetch ?: return
            startFetchLocked(force, fetch)
        }
        launch(start)
    }

    fun freshLocked(staleTime: Duration, gcTime: Duration): T? {
        noteGcLocked(gcTime)
        return if (data != null && !isStaleLocked(staleTime)) data else null
    }

    /** sync [cache]: store a value computed by the caller */
    fun remember(value: T, gcTime: Duration) {
        val notify = synchronized(lock) {
            if (detached) return
            noteGcLocked(gcTime)
            setSuccessLocked(value)
            if (leases.isEmpty()) scheduleGcLocked()
            listeners.toList()
        }
        notify.forEach { it() }
    }

    fun seed(value: T, freshFor: Duration) {
        val notify = synchronized(lock) {
            if (detached) return
            job?.cancel()
            job = null
            collect?.cancel()
            collect = null
            setSuccessLocked(value)
            seedFreshFor = freshFor
            listeners.toList()
        }
        notify.forEach { it() }
    }

    /** Dropped by [CacheStore.clear]: cancels everything, resets to Pending, notifies once, then stays inert. */
    fun detach() {
        val notify = synchronized(lock) {
            detached = true
            job?.cancel()
            job = null
            collect?.cancel()
            collect = null
            data = null
            error = null
            status = CacheStatus.Pending
            isFetching = false
            updatedAt = null
            seedFreshFor = null
            leases.clear()
            listeners.toList()
        }
        notify.forEach { it() }
    }

    private fun noteLocked(query: CacheQuery<T>) {
        noteGcLocked(query.gcTime)
        lastFetch = query.fetch
    }

    private fun noteGcLocked(value: Duration) {
        val current = gcTime
        if (current == null || value > current) gcTime = value
    }

    private fun setSuccessLocked(value: T) {
        seedFreshFor = null
        data = value
        error = null
        status = CacheStatus.Success
        isFetching = job != null
        updatedAt = store.config.nowMillis()
    }

    private fun startFetchLocked(force: Boolean, body: suspend () -> T): Job? {
        if (detached) return null
        // `job` is set before the job starts and cleared when it completes: "in flight" = non-null
        if (job != null && !force) return null
        job?.cancel()
        isFetching = true
        val launched = store.config.scope.launch(start = CoroutineStart.LAZY) { run(body) }
        job = launched
        return launched
    }

    /** Outside the lock: publish isFetching, then run the fetch (an immediate dispatcher runs it right here). */
    private fun launch(job: Job?) {
        if (job == null) return
        publish()
        // also fires for a job that never ran (its scope's Job was cancelled): isFetching must not stick
        job.invokeOnCompletion { finished(job) }
        job.start()
    }

    private suspend fun run(body: suspend () -> T) {
        val self = currentCoroutineContext()[Job]
        val outcome = try {
            Result.success(body())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
        val notify = synchronized(lock) {
            // superseded by a refetch, a seed, a clear or an eviction: the result belongs to nobody
            if (job !== self || detached) return
            job = null
            outcome.fold(
                onSuccess = { setSuccessLocked(it) },
                onFailure = {
                    error = it
                    status = CacheStatus.Error
                },
            )
            isFetching = false
            listeners.toList()
        }
        notify.forEach { it() }
    }

    private fun finished(done: Job) {
        val notify = synchronized(lock) {
            if (job !== done) return
            job = null
            isFetching = false
            listeners.toList()
        }
        notify.forEach { it() }
    }

    private fun isStaleLocked(staleTime: Duration?): Boolean {
        val at = updatedAt ?: return true
        val window = seedFreshFor ?: staleTime ?: leases.minOfOrNull { it.query.staleTime } ?: Duration.ZERO
        return store.config.nowMillis() - at >= window.inWholeMilliseconds
    }

    private fun scheduleGcLocked() {
        val wait = gcTime ?: return
        collect?.cancel()
        collect = null
        if (wait == Duration.ZERO) {
            evictLocked()
            return
        }
        val countdown = store.config.scope.launch(start = CoroutineStart.LAZY) {
            delay(wait)
            val self = currentCoroutineContext()[Job]
            synchronized(lock) {
                if (collect === self && leases.isEmpty() && !detached) evictLocked()
            }
        }
        collect = countdown
        countdown.start()
    }

    private fun publish() {
        synchronized(lock) { listeners.toList() }.forEach { it() }
    }

    private fun evictLocked() {
        detached = true
        job?.cancel()
        job = null
        collect?.cancel()
        collect = null
        store.removeLocked(identity, this)
    }
}
