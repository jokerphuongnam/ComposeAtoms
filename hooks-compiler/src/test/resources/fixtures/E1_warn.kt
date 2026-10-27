package fixture
import compose.hooks.*

fun rememberRow(id: Int): Int = customRemember { id }

fun warn(ids: List<Int>) = customRemember {
    for (id in ids) rememberRow(id)                     // E1 warning (custom hook in loop without key)
    ids.forEach { rememberRow(it) }                     // E1 warning
    Unit
}
