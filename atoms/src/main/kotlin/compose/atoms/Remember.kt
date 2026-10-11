package compose.atoms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Custom hook. The composable reads [atom] and recomposes when that atom changes.
 * Dropping the composable unsubscribes it. The atom does not keep the composable on screen.
 */
@Composable
fun <T> rememberAtom(atom: Atom<T>): AtomState<T> {
    val state = remember(atom) { mutableStateOf(atom.read()) }
    DisposableEffect(atom) {
        val unsubscribe = atom.subscribe {
            state.value = atom.read()
        }
        onDispose(unsubscribe)
    }
    return AtomState(state.value, atom::write)
}
