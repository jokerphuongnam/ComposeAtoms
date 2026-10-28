package fixture
import compose.hooks.*
import androidx.compose.runtime.key


fun bad(flag: Boolean, ids: List<Int>) = customRemember {
    if (flag) rememberState { 1 }                       // E1 error (if)
    when { flag -> rememberEffect { } }                 // E1 error (when)
    for (id in ids) rememberRef { id }                  // E1 error (loop)
    ids.forEach { key(it) { rememberState { it } } }    // E1 error (key does not help slots)
    try { rememberState { 2 } } catch (e: Exception) {} // E1 error (try)
    Unit
}
