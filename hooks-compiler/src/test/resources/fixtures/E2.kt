package fixture
import compose.hooks.*

private val Missing = hookContextOf<String>("Missing")
private val WithDefault = hookContextOf<String>("D") { "d" }
private val Provided = hookContextOf<String>("P")
val Public = hookContextOf<String>("Public")

fun e2() = customRemember {
    rememberContext(Missing)       // E2 error
    rememberContext(WithDefault)   // ok
    rememberContext(Provided)      // ok, provided below
    rememberContext(Public)        // not checked (may be provided in another file)
    Provided.Provider("x") {}
}
