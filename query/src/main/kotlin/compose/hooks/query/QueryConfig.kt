package compose.hooks.query

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Where a [QueryClient]'s cache work runs and which clock it reads.
 *
 * - [context]: fetch bodies and gcTime countdowns are launched in `CoroutineScope(context + SupervisorJob(context[Job]))`.
 *   Default [Dispatchers.Default]. Any dispatcher works (`Dispatchers.IO`, `Dispatchers.Main`, your own pool, a
 *   `TestDispatcher`). If [context] carries a Job, cache work becomes its child and stops when it is cancelled.
 *   Entry state is guarded by the client's lock, so any (multi-threaded) dispatcher is safe.
 * - [nowMillis]: the clock for staleTime (when data was fetched / seeded and whether it is still fresh).
 *   Default wall clock. gcTime uses `delay()` on [context], so it follows that dispatcher's time.
 *
 * ```
 * QueryConfig.global = QueryConfig(context = Dispatchers.IO)                         // the Default client
 * val screenClient = QueryClient(QueryConfig(Dispatchers.IO.limitedParallelism(2)))  // one QueryClientProvider
 * QueryClient(QueryConfig(StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }) // virtual time
 * ```
 *
 * Changing a client's config affects work started afterwards: a fetch or countdown already running stays on the old
 * context, and existing entries keep the timestamps of the old clock (call [QueryClient.clear] when switching clocks).
 */
class QueryConfig(
    /** Where fetch bodies and gcTime countdowns run. */
    val context: CoroutineContext = Dispatchers.Default,
    /** The clock for staleTime, in ms. */
    val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    internal val scope: CoroutineScope = CoroutineScope(context + SupervisorJob(context[Job]))

    /** A config with some fields replaced. */
    fun copy(context: CoroutineContext = this.context, nowMillis: () -> Long = this.nowMillis): QueryConfig =
        QueryConfig(context, nowMillis)

    /** Holds [global]. */
    companion object {
        /** The config of [QueryClient.Default] (same as `QueryClient.Default.config`); safe to set from any thread. */
        @JvmStatic
        var global: QueryConfig
            get() = QueryClient.Default.config
            set(value) {
                QueryClient.Default.config = value
            }
    }
}
