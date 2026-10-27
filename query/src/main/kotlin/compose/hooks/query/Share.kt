package compose.hooks.query

import androidx.compose.runtime.Composable
import compose.hooks.customRemember
import compose.hooks.rememberEffect
import compose.hooks.rememberMemo
import java.lang.ref.WeakReference

private val shareStore = AtomStore()
private val shares = HashMap<String, ShareEntry>()

private class ShareEntry(atom: Atom<Any?>) {
    val weak = WeakReference(atom)
    var strong: Atom<Any?>? = null
    var users = 0
}

/**
 * One atom for this id and [T]. The same id with another type is another atom.
 *
 * Lifetime: reference counted by [rememberShareState] users (each user = one hook scope / owner). When the last
 * user's owner is cleared the atom and its value are released, and the next user starts from `create()` again.
 * A plain (non-composable) caller holding the returned [Atom] keeps it reachable like before (weak entry).
 */
inline fun <reified T> shareState(id: String, noinline create: () -> T): Atom<T> =
    shareAtom("$id\u0000${T::class.java.name}", create)

@Composable
inline fun <reified T> rememberShareState(id: String, noinline create: () -> T): AtomState<T> = customRemember {
    val atom = rememberMemo(id, T::class.java.name) { shareState(id, create) }
    rememberEffect(atom) {
        retainShare(atom)
        val release = { releaseShare(atom) }
        release
    }
    rememberAtom(atom)
}

@Suppress("UNCHECKED_CAST")
fun <T> shareAtom(identity: String, create: () -> T): Atom<T> = synchronized(shares) {
    val existing = shares[identity]?.weak?.get()
    if (existing != null) return existing as Atom<T>
    shareStore.remove(identity)
    val created = Atom(shareStore, identity, create)
    shares[identity] = ShareEntry(created as Atom<Any?>)
    created
}

/** Number of live users of a shared atom (tests / debugging). */
fun shareUsers(atom: Atom<*>): Int = synchronized(shares) { shares[atom.identity as String]?.users ?: 0 }

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal fun retainShare(atom: Atom<*>) = synchronized(shares) {
    val id = atom.identity as String
    val entry = shares[id]?.takeIf { it.weak.get() === atom }
        ?: ShareEntry(atom as Atom<Any?>).also { shares[id] = it }
    entry.users++
    entry.strong = atom as Atom<Any?>
}

@PublishedApi
internal fun releaseShare(atom: Atom<*>) = synchronized(shares) {
    val id = atom.identity as String
    val entry = shares[id]?.takeIf { it.weak.get() === atom } ?: return@synchronized
    entry.users = (entry.users - 1).coerceAtLeast(0)
    if (entry.users == 0) {
        entry.strong = null
        shares.remove(id)
        shareStore.remove(id)
    }
}
