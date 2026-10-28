package compose.hooks.query

/**
 * One piece of state, like a Jotai atom. Create it outside composition so its identity stays put.
 * [rememberAtom] subscribes the current composable. Writing notifies every subscriber.
 */
class Atom<T> internal constructor(
    private val store: AtomStore,
    internal val identity: Any,
    internal val shares: ShareRegistry? = null,
    private val create: () -> T,
) {
    /** The current value (created with the atom's initializer on first read). */
    fun read(): T = store.read(identity, create)

    /** Replaces the value and notifies every subscriber (any thread). */
    fun write(value: T) {
        store.write(identity, value)
    }

    /** `write(block(read()))`. */
    fun update(block: (T) -> T) {
        write(block(read()))
    }

    internal fun subscribe(listener: () -> Unit): () -> Unit = store.subscribe(identity, listener)
}

/** Value + setter returned by [rememberAtom]; `val (value, set) = rememberGlobalState { 0 }` also works. */
class AtomState<T>(
    /** The value at the time of this composition. */
    val value: T,
    /** Writes the atom (stable for the atom). */
    val set: (T) -> Unit,
) {
    /** `set(block(value))`. */
    fun update(block: (T) -> T) {
        set(block(value))
    }

    /** `val session by rememberGlobalState { Session("Ada") }` */
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = value

    /** `val (value, set) = ...`: [value]. */
    operator fun component1(): T = value
    /** `val (value, set) = ...`: [set]. */
    operator fun component2(): (T) -> Unit = set
}

/** Values + listeners of a set of atoms. Thread-safe: maps are guarded by this store, listeners run outside the lock. */
internal class AtomStore {
    private val values = HashMap<Any, Any?>()
    private val present = HashSet<Any>()
    private val listeners = HashMap<Any, MutableSet<() -> Unit>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> read(identity: Any, create: () -> T): T = synchronized(this) {
        if (identity !in present) {
            values[identity] = create()
            present.add(identity)
        }
        values[identity] as T
    }

    fun <T> write(identity: Any, value: T) {
        val notify = synchronized(this) {
            values[identity] = value
            present.add(identity)
            listeners[identity].orEmpty().toList()
        }
        notify.forEach { it() }
    }

    /** Forgets every value (the next read runs `create` again) and notifies the subscribers of those values. */
    fun reset() {
        val notify = synchronized(this) {
            val identities = present.toList()
            values.clear()
            present.clear()
            identities.flatMap { id -> listeners[id].orEmpty().toList() }
        }
        notify.forEach { it() }
    }

    fun remove(identity: Any) = synchronized(this) {
        values.remove(identity)
        present.remove(identity)
        listeners.remove(identity)
    }

    fun subscribe(identity: Any, listener: () -> Unit): () -> Unit = synchronized(this) {
        val set = listeners.getOrPut(identity) { LinkedHashSet() }
        set.add(listener)
        return@synchronized {
            synchronized(this) {
                set.remove(listener)
                // only drop the set this handle belongs to: after remove(identity) a newer set may be registered
                if (set.isEmpty() && listeners[identity] === set) listeners.remove(identity)
            }
        }
    }
}
