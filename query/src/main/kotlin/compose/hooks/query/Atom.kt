package compose.hooks.query

/**
 * One piece of state, like a Jotai atom. Create it outside composition so its identity stays put.
 * [rememberAtom] subscribes the current composable. Writing notifies every subscriber.
 */
class Atom<T> internal constructor(
    private val store: AtomStore,
    internal val identity: Any,
    private val create: () -> T,
) {
    fun read(): T = store.read(identity, create)

    fun write(value: T) {
        store.write(identity, value)
    }

    fun update(block: (T) -> T) {
        write(block(read()))
    }

    internal fun subscribe(listener: () -> Unit): () -> Unit = store.subscribe(identity, listener)
}

/** Value + setter returned by [rememberAtom]; `val (value, set) = rememberGlobalState { 0 }` also works. */
class AtomState<T>(
    val value: T,
    val set: (T) -> Unit,
) {
    fun update(block: (T) -> T) {
        set(block(value))
    }

    /** `val session by rememberGlobalState { Session("Ada") }` */
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = value

    operator fun component1(): T = value
    operator fun component2(): (T) -> Unit = set
}

internal class AtomStore {
    private val values = HashMap<Any, Any?>()
    private val present = HashSet<Any>()
    private val listeners = HashMap<Any, MutableSet<() -> Unit>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> read(identity: Any, create: () -> T): T {
        if (identity !in present) {
            values[identity] = create()
            present.add(identity)
        }
        return values[identity] as T
    }

    fun <T> write(identity: Any, value: T) {
        values[identity] = value
        present.add(identity)
        listeners[identity].orEmpty().toList().forEach { it() }
    }

    fun remove(identity: Any) {
        values.remove(identity)
        present.remove(identity)
        listeners.remove(identity)
    }

    fun subscribe(identity: Any, listener: () -> Unit): () -> Unit {
        val set = listeners.getOrPut(identity) { LinkedHashSet() }
        set.add(listener)
        return {
            set.remove(listener)
            if (set.isEmpty()) listeners.remove(identity)
        }
    }
}
