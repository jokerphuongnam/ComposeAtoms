package fixture
import compose.hooks.*
import compose.hooks.query.*

fun plain(): Int = 1

fun moreBad(flag: Boolean, ids: List<Int>) = customRemember {
    repeat(2) { rememberState { it } }                 // E1 error (repeat)
    var n = 0
    while (n < 1) { rememberRef { n }; n++ }           // E1 error (while)
    ids.map(transform = { rememberState { it } })      // E1 error (named-argument lambda)
    if (flag) rememberAtom(1)                          // E1 error (query slot hook)
    do { rememberId() } while (false)                  // E1 error (do-while)
    if (flag) ParentState("p") {}                      // E1 error (query provider)
    while (rememberRef { 7 }.isEmpty()) { plain() }    // E1 error (loop condition runs every iteration)
    try { plain() } catch (e: Exception) { rememberState { 8 } } // E1 error (catch runs only on failure)
    takesBlock { if (flag) rememberState { 3 } }       // fine: SAM lambda may run anywhere, not checked
    Unit
}
