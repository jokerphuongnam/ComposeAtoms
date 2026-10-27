package compose.hooks.query

import androidx.compose.runtime.Composable

private val globalStore = AtomStore()
private val globals = HashMap<String, Atom<Any?>>()

/**
 * One atom for [T] until the process exits. Calling this again returns that same atom.
 * A different type is a different atom. The store does not retain a composable.
 */
inline fun <reified T> globalState(noinline create: () -> T): Atom<T> {
    return globalAtom(T::class.java.name, create)
}

@Composable
inline fun <reified T> rememberGlobalState(noinline create: () -> T): AtomState<T> {
    return rememberAtom(globalState(create))
}

@Suppress("UNCHECKED_CAST")
fun <T> globalAtom(type: String, create: () -> T): Atom<T> {
    val existing = globals[type]
    if (existing != null) return existing as Atom<T>
    val created = Atom(globalStore, type, create)
    globals[type] = created as Atom<Any?>
    return created
}
