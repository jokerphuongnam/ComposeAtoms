package compose.hooks.query

import androidx.compose.runtime.Composable

/**
 * One atom for [T] in [client] until the client is reset ([QueryClient.resetGlobalStates]) or the process exits.
 * Calling this again returns that same atom. A different type is a different atom. The store does not retain a
 * composable.
 */
inline fun <reified T> globalState(client: QueryClient = QueryClient.Default, noinline create: () -> T): Atom<T> {
    return client.globalAtom(T::class.java.name, create)
}

/** [globalState] in the current [LocalQueryClient], observed by the caller. */
@Composable
inline fun <reified T> rememberGlobalState(noinline create: () -> T): AtomState<T> {
    return rememberAtom(globalState(LocalQueryClient.current, create))
}

/** [QueryClient.globalAtom] of [QueryClient.Default]. */
fun <T> globalAtom(type: String, create: () -> T): Atom<T> = QueryClient.Default.globalAtom(type, create)

/** [QueryClient.resetGlobalStates] of [QueryClient.Default] (logout, account switch, a test's teardown). */
fun resetGlobalStates() {
    QueryClient.Default.resetGlobalStates()
}
