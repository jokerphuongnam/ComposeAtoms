// Minimal stand-ins for the runtime APIs the checkers resolve by fully-qualified name.
@file:Suppress("UNUSED_PARAMETER")
package compose.hooks

class RememberContext<T>
fun <T> rememberContextOf(name: String = "", default: (() -> T)? = null): RememberContext<T> = RememberContext()
fun <R> customRemember(key: Any? = null, block: () -> R): R = block()
fun <T> rememberState(init: () -> T): Array<T> = TODO()
fun <R> rememberEffect(vararg deps: Any?, block: () -> R) {}
fun <T> rememberRef(init: () -> T): Array<T> = TODO()
fun <T> rememberContext(context: RememberContext<T>): T = TODO()
fun <T> RememberContext<T>.Provider(value: T, content: () -> Unit) {}
fun rememberId(): String = ""
fun interface Block { fun run() }
fun takesBlock(block: Block) {}
