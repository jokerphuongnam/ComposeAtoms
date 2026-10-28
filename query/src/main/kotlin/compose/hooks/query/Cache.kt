package compose.hooks.query

import androidx.compose.runtime.Composable
import compose.hooks.customRemember
import compose.hooks.rememberEffect
import compose.hooks.rememberState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class CacheStatus { Pending, Success, Error }

class CacheResult<T>(
    val data: T?,
    val error: Throwable?,
    val isFetching: Boolean,
    val status: CacheStatus,
) {
    val isPending: Boolean get() = status == CacheStatus.Pending
    val isSuccess: Boolean get() = status == CacheStatus.Success
    val isError: Boolean get() = status == CacheStatus.Error
}

/**
 * A query atom. The string [key] plus [T] is the identity. The closure is not compared.
 * Same key and same type share one in-flight fetch. Same key and another type are another cache.
 */
class CacheQuery<T> internal constructor(
    internal val entry: CacheEntry<T>,
) {
    fun snapshot(): CacheResult<T> = entry.snapshot()

    fun refetch() {
        entry.fetch(force = true)
    }

    internal fun subscribe(listener: () -> Unit): () -> Unit = entry.subscribe(listener)

    internal fun retain() {
        entry.retain()
    }

    internal fun release() {
        entry.release()
    }

    // a CacheQuery is a handle: two handles to the same entry are the same query (stable effect deps)
    override fun equals(other: Any?): Boolean = other is CacheQuery<*> && other.entry === entry
    override fun hashCode(): Int = System.identityHashCode(entry)
}

inline fun <reified T> cache(
    key: String,
    staleTime: Duration = Duration.ZERO,
    gcTime: Duration = 5.minutes,
    noinline fetch: suspend () -> T,
): CacheQuery<T> {
    return CacheRegistry.lease(key, T::class.java.name, staleTime, gcTime, fetch)
}

/** Runs [fetch] on the caller and returns the value. A fresh entry skips [fetch]. */
inline fun <reified T> cache(
    key: String,
    staleTime: Duration = Duration.ZERO,
    gcTime: Duration,
    noinline fetch: () -> T,
): T {
    CacheRegistry.fresh<T>(key, T::class.java.name, staleTime, gcTime)?.let { return it }
    val value = fetch()
    CacheRegistry.store(key, T::class.java.name, value, staleTime, gcTime)
    return value
}

/**
 * Subscribes the caller's hook scope to [query]. Built on core hooks: the observer is a [rememberEffect] whose
 * cleanup unsubscribes and releases, so the query stays retained while the caller's owner lives (also while the
 * screen is on the back stack) and the `gcTime` countdown starts when the owner is cleared (pop).
 */
@Composable
fun <T> rememberCache(query: CacheQuery<T>): CacheResult<T> = customRemember {
    val tick = rememberState { 0 }
    rememberEffect(query) {
        // subscribe before retain: a fetch that completes synchronously (immediate dispatcher) must not be missed
        val unsubscribe = query.subscribe { tick.value++ }
        query.retain()
        val cleanup = {
            unsubscribe()
            query.release()
        }
        cleanup
    }
    tick.value
    query.snapshot()
}

internal object CacheClock {
    var nowMillis: () -> Long = { System.currentTimeMillis() }
}

object CacheRegistry {
    private val entries = HashMap<String, CacheEntry<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> lease(
        key: String,
        type: String,
        staleTime: Duration,
        gcTime: Duration,
        fetch: suspend () -> T,
    ): CacheQuery<T> {
        val identity = "$key\u0000$type"
        val existing = entries[identity] as CacheEntry<T>?
        if (existing != null) {
            existing.fetchBody = fetch
            existing.staleTime = staleTime
            existing.gcTime = gcTime
            return CacheQuery(existing)
        }
        val created = CacheEntry(identity, fetch, staleTime, gcTime)
        entries[identity] = created
        return CacheQuery(created)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> fresh(key: String, type: String, staleTime: Duration, gcTime: Duration): T? {
        synchronized(this) {
            val entry = entries["$key\u0000$type"] as CacheEntry<T>? ?: return null
            entry.staleTime = staleTime
            entry.gcTime = gcTime
            return entry.freshValue()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> store(key: String, type: String, value: T, staleTime: Duration, gcTime: Duration) {
        synchronized(this) {
            val identity = "$key\u0000$type"
            val existing = entries[identity] as CacheEntry<T>?
            val entry = if (existing != null) {
                existing.staleTime = staleTime
                existing.gcTime = gcTime
                existing
            } else {
                CacheEntry(identity, { value }, staleTime, gcTime).also { entries[identity] = it }
            }
            entry.remember(value)
        }
    }

    internal fun remove(identity: String, entry: CacheEntry<*>) {
        if (entries[identity] === entry) entries.remove(identity)
    }
}

internal class CacheEntry<T>(
    private val identity: String,
    var fetchBody: suspend () -> T,
    var staleTime: Duration,
    var gcTime: Duration,
) {
    private var data: T? = null
    private var error: Throwable? = null
    private var isFetching = false
    private var status = CacheStatus.Pending
    private var updatedAt: Long? = null
    private var observers = 0
    private var job: Job? = null
    private var collect: Job? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    fun snapshot(): CacheResult<T> = CacheResult(data, error, isFetching, status)

    fun freshValue(): T? = if (data != null && !isStale()) data else null

    fun remember(value: T) {
        data = value
        error = null
        status = CacheStatus.Success
        isFetching = false
        updatedAt = CacheClock.nowMillis()
        publish()
        if (observers != 0) return
        if (gcTime == Duration.ZERO) {
            evict()
            return
        }
        val wait = gcTime
        collect?.cancel()
        collect = cacheScope.launch {
            delay(wait)
            if (observers == 0) evict()
        }
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun retain() {
        observers += 1
        collect?.cancel()
        collect = null
        if (data == null || isStale()) fetch(force = false)
    }

    fun release() {
        observers = (observers - 1).coerceAtLeast(0)
        if (observers != 0) return
        if (gcTime == Duration.ZERO) {
            evict()
            return
        }
        val wait = gcTime
        collect = cacheScope.launch {
            delay(wait)
            if (observers == 0) evict()
        }
    }

    fun fetch(force: Boolean) {
        if (job?.isActive == true && !force) return
        job?.cancel()
        val body = fetchBody
        isFetching = true
        publish()
        // LAZY: `job` must point at this fetch before it can run (an immediate dispatcher would finish it first)
        val launched = cacheScope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                val value = body()
                data = value
                error = null
                status = CacheStatus.Success
                updatedAt = CacheClock.nowMillis()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                this@CacheEntry.error = error
                status = CacheStatus.Error
            } finally {
                // a refetch may have replaced this (cancelled) fetch: leave the newer one's state alone
                if (job === self) job = null
                if (job == null) {
                    isFetching = false
                    publish()
                }
            }
        }
        job = launched
        launched.start()
    }

    suspend fun awaitValue(): T {
        if (data != null && !isStale()) return data as T
        if (job?.isActive != true) fetch(force = false)
        job?.join()
        error?.let { throw it }
        return data as T
    }

    private fun isStale(): Boolean {
        val at = updatedAt ?: return true
        return CacheClock.nowMillis() - at >= staleTime.inWholeMilliseconds
    }

    private fun publish() {
        listeners.toList().forEach { it() }
    }

    private fun evict() {
        job?.cancel()
        job = null
        collect?.cancel()
        collect = null
        CacheRegistry.remove(identity, this)
    }
}

internal var cacheScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
