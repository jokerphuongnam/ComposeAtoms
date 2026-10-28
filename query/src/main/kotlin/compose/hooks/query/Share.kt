package compose.hooks.query

import androidx.compose.runtime.Composable
import compose.hooks.customRemember
import compose.hooks.rememberEffect
import compose.hooks.rememberMemo
import java.lang.ref.WeakReference

/** The share atoms of one [QueryClient]. */
internal class ShareRegistry {
    val store = AtomStore()
    val entries = HashMap<String, ShareEntry>()
}

internal class ShareEntry(atom: Atom<Any?>) {
    val weak = WeakReference(atom)
    var strong: Atom<Any?>? = null
    var users = 0
}

/**
 * One atom for this id and [T] in [client]. The same id with another type is another atom.
 *
 * Lifetime: reference counted by [rememberShareState] users (each user = one hook scope / owner). When the last
 * user's owner is cleared the atom and its value are released, and the next user starts from `create()` again.
 * A plain (non-composable) caller holding the returned [Atom] keeps it reachable like before (weak entry).
 */
inline fun <reified T> shareState(
    id: String,
    client: QueryClient = QueryClient.Default,
    noinline create: () -> T,
): Atom<T> = shareAtom("$id\u0000${T::class.java.name}", client, create)

/**
 * [shareState] in the current [LocalQueryClient], observed and ref-counted by the caller: the share lives while at
 * least one caller's owner lives (hidden screens on the back stack count), and is dropped after the last one is
 * cleared, so the next user starts again from `create`. Changing [id] releases the old share and retains the new one.
 */
@Composable
inline fun <reified T> rememberShareState(id: String, noinline create: () -> T): AtomState<T> =
    rememberShareStateOf(id, T::class.java.name, create)

@PublishedApi
@Composable
internal fun <T> rememberShareStateOf(id: String, type: String, create: () -> T): AtomState<T> {
    val client = LocalQueryClient.current
    return customRemember(name = "rememberShareState") {
        val atom = rememberMemo(id, type, client) { shareAtom("$id\u0000$type", client, create) }
        rememberEffect(atom) {
            retainShare(atom)
            val release = { releaseShare(atom) }
            release
        }
        rememberAtom(atom)
    }
}

/**
 * The atom shared under [identity] in [client] (created with [create] on first use). Lower level than
 * [shareState] / [rememberShareState]: no retain / release, the atom lives until [QueryClient.clear].
 */
@Suppress("UNCHECKED_CAST")
fun <T> shareAtom(identity: String, client: QueryClient = QueryClient.Default, create: () -> T): Atom<T> {
    val registry = client.shares
    return synchronized(registry) {
        val existing = registry.entries[identity]?.weak?.get()
        if (existing != null) return existing as Atom<T>
        registry.store.remove(identity)
        val created = Atom(registry.store, identity, registry, create)
        registry.entries[identity] = ShareEntry(created as Atom<Any?>)
        created
    }
}

/** Number of live users of a shared atom (tests / debugging). */
fun shareUsers(atom: Atom<*>): Int {
    val registry = atom.shares ?: return 0
    return synchronized(registry) { registry.entries[atom.identity as String]?.users ?: 0 }
}

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal fun retainShare(atom: Atom<*>) {
    val registry = atom.shares ?: return
    synchronized(registry) {
        val id = atom.identity as String
        val entry = registry.entries[id]?.takeIf { it.weak.get() === atom }
            ?: ShareEntry(atom as Atom<Any?>).also { registry.entries[id] = it }
        entry.users++
        entry.strong = atom as Atom<Any?>
    }
}

@PublishedApi
internal fun releaseShare(atom: Atom<*>) {
    val registry = atom.shares ?: return
    synchronized(registry) {
        val id = atom.identity as String
        val entry = registry.entries[id]?.takeIf { it.weak.get() === atom } ?: return
        entry.users = (entry.users - 1).coerceAtLeast(0)
        if (entry.users == 0) {
            entry.strong = null
            registry.entries.remove(id)
            registry.store.remove(id)
        }
    }
}
