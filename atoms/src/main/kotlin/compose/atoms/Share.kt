package compose.atoms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.lang.ref.WeakReference

private val shareStore = AtomStore()
private val shares = HashMap<String, WeakReference<Atom<Any?>>>()

/**
 * One atom for this id and [T]. The same id with another type is another atom.
 * The atom stays while a caller holds the returned [Atom], and after the last holder is gone it can be collected.
 * Callers usually keep it with [rememberShareState]. The atom does not retain a composable.
 */
inline fun <reified T> shareState(id: String, noinline create: () -> T): Atom<T> {
    return shareAtom("$id\u0000${T::class.java.name}", create)
}

@Composable
inline fun <reified T> rememberShareState(id: String, noinline create: () -> T): AtomState<T> {
    val atom = remember(id, T::class.java.name) { shareState(id, create) }
    return rememberAtom(atom)
}

@Suppress("UNCHECKED_CAST")
fun <T> shareAtom(identity: String, create: () -> T): Atom<T> {
    val existing = shares[identity]?.get()
    if (existing != null) return existing as Atom<T>
    val created = Atom(shareStore, identity, create)
    shares[identity] = WeakReference(created as Atom<Any?>)
    return created
}
