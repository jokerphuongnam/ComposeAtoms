package fixture
import compose.hooks.*
import androidx.compose.runtime.key

class Item(val id: Int)
fun rememberItem(id: Int): Int = customRemember { rememberState { id }; id }

fun ok(flag: Boolean, items: List<Item>) = customRemember {
    val a = rememberState { 0 }
    rememberEffect(flag) { if (flag) println(a) }
    if (flag) { rememberItem(1) }            // custom hook under if: own child scope -> fine
    for (it in items) key(it.id) { rememberItem(it.id) } // keyed loop -> fine
    val b = if (flag) 1 else 2               // plain locals are fine
    b
}

fun bare(flag: Boolean) {
    if (flag) rememberState { 1 }            // outside customRemember: implicit scope per call site -> fine
}
