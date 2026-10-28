package fixture
import compose.hooks.*

fun shortCircuit(flag: Boolean, s: String?, maybe: RememberContext<String>?) = customRemember {
    val a = flag && rememberRef { true }.isEmpty()                  // E1 error (right of &&)
    val b = flag || rememberRef { true }.isEmpty()                  // E1 error (right of ||)
    val c = s ?: rememberId()                                       // E1 error (right of ?:)
    when {
        flag -> Unit
        rememberRef { 1 }.isEmpty() -> Unit                         // E1 error (later when condition)
        else -> Unit
    }
    if (flag) Unit else if (rememberRef { 2 }.isEmpty()) Unit       // E1 error (else-if condition)
    maybe?.Provider("x") {}                                         // E1 error (safe-call selector)
    listOf(a, b, c)
}
