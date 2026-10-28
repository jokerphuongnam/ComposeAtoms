package fixture
import compose.hooks.*
import androidx.compose.runtime.key

fun rememberTile(id: Int): Int = customRemember { id }

fun moreWarn(ids: List<Int>) = customRemember {
    repeat(3) { rememberTile(it) }                      // E1 warning (repeat)
    ids.map { rememberTile(it) }                        // E1 warning (map)
    ids.forEach { key(it) { rememberTile(it) } }        // keyed: fine
    customRemember { rememberTile(0) }                  // nested customRemember: unconditional
    Unit
}
