package fixture
import compose.hooks.*
import compose.hooks.query.*
import androidx.compose.runtime.remember

fun rememberCell(id: Int): Int = customRemember { id }
fun plain(): Int = 1

fun moreOk(flag: Boolean, ids: List<Int>) = customRemember {
    if (flag) { rememberContextOf<String>("c") }       // context factory takes no slot
    if (flag) remember { 1 }                            // compose remember is positional by design
    if (flag) rememberCell(1)                           // custom hook under if: own child scope
    fun local() { if (flag) rememberState { 1 } }      // local fun: own function, not checked
    val stored = { if (flag) rememberState { 2 } }      // lambda stored as a value: not checked
    run { if (flag) rememberState { 4 } }               // other higher-order owner: not checked
    if (rememberState { 5 }.isEmpty()) plain()          // hook in the condition runs every pass
    try { plain() } finally { rememberState { 6 } }     // finally runs every pass
    try { plain() } catch (e: Exception) { plain() }   // no hook in catch
    ids.forEach { println(it) }                         // loop lambda without hooks
    rememberGlobalState { 0 }                           // unconditional query hook
    for (id in ids) plain()
    local(); stored()
    rememberParentState<String>()
    Unit
}
