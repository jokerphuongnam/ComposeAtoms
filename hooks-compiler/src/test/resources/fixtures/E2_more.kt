package fixture.e2
import compose.hooks.*

private val Ctx = rememberContextOf<String>("Ctx")
private val Alias = Ctx
private val Lazy: RememberContext<String> by lazy { rememberContextOf<String>("Lazy") }
private val Made = makeCtx()
private fun makeCtx() = rememberContextOf<String>("Made")
internal val Internal = rememberContextOf<String>("Internal")
private val Named = rememberContextOf<String>(name = "Named", default = { "n" })
object Holder { val ctx = rememberContextOf<String>("Holder") }

fun e2more() = customRemember {
    val local = rememberContextOf<String>("local")
    rememberContext(local)                 // local: not checked
    rememberContext(Alias)                 // initializer is not a call: not checked
    rememberContext(Lazy)                  // delegated: not checked
    rememberContext(Made)                  // initializer is another function: not checked
    rememberContext(Internal)              // not private
    rememberContext(Named)                 // named default argument
    rememberContext(Holder.ctx)            // not a top-level property of this file
    rememberContext(makeCtx())             // not a property access
    rememberContext(Ctx)                   // E2 error
    rememberContextOf<String>("t").Provider("x") {} // receiver is not a property
}
