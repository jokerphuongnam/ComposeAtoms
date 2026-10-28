package compose.hooks.query

import androidx.compose.runtime.Composable
import compose.hooks.rememberEffect
import compose.hooks.rememberState

/**
 * Subscribes the caller's hook scope to [atom] and returns the current value with a setter.
 *
 * Built on core hooks: the value lives in a [rememberState] slot and the subscription in a [rememberEffect] whose
 * cleanup unsubscribes. The subscription therefore lasts as long as the caller's owner (until the screen is popped),
 * not just while the composable is on screen; a screen coming back from the back stack sees the latest value
 * without a flash of an old one.
 */
@Composable
fun <T> rememberAtom(atom: Atom<T>): AtomState<T> {
    val state = rememberState { atom.read() }
    rememberEffect(atom) {
        state.value = atom.read() // catch writes between first composition and subscription
        val unsubscribe = atom.subscribe { state.value = atom.read() }
        unsubscribe
    }
    return AtomState(state.value, atom::write)
}
