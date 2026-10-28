package fixture
import compose.hooks.*

private val Missing = rememberContextOf<String>("Missing")
private val WithDefault = rememberContextOf<String>("D") { "d" }
private val Provided = rememberContextOf<String>("P")
val Public = rememberContextOf<String>("Public")

fun e2() = customRemember {
    rememberContext(Missing)       // E2 error
    rememberContext(WithDefault)   // ok
    rememberContext(Provided)      // ok, provided below
    rememberContext(Public)        // not checked (may be provided in another file)
    Provided.Provider("x") {}
}
