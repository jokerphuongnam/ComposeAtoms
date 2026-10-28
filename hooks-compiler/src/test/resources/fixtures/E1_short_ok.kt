package fixture
import compose.hooks.*

fun shortOk(flag: Boolean, s: String?) = customRemember {
    val first = rememberRef { true }.isEmpty() && flag              // left of && always runs
    val id = rememberId()
    val name = s ?: id                                              // the hook ran before the elvis
    when {
        rememberRef { 1 }.isEmpty() -> Unit                         // the first condition always runs
        else -> Unit
    }
    if (rememberRef { 2 }.isEmpty()) Unit                           // so does an if condition
    when (rememberId()) {                                           // and a when subject
        "a" -> Unit
        else -> Unit
    }
    listOf(first, name)
}
