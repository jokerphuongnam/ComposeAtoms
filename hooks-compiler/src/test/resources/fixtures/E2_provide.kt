package fixture
import compose.hooks.*
import compose.hooks.test.*

private val ViaProvides = rememberContextOf<String>("A")
private val ViaProvide = rememberContextOf<String>("B")
private val NotProvided = rememberContextOf<String>("C")

fun e2Provide() = customRemember {
    rememberContext(ViaProvides)   // ok: `provides` (hooks-test) below
    rememberContext(ViaProvide)    // ok: `provide(...)` (hooks-test) below
    rememberContext(NotProvided)   // E2 error
    listOf(ViaProvides provides "x", provide(ViaProvide, "y"))
}
