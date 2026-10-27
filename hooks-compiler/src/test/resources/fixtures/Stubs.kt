// Minimal stand-ins for the runtime APIs the checkers resolve by fully-qualified name.
@file:Suppress("UNUSED_PARAMETER")
package compose.hooks

class HookContext<T>
fun <T> hookContextOf(name: String = "", default: (() -> T)? = null): HookContext<T> = HookContext()
fun <R> customRemember(key: Any? = null, block: () -> R): R = block()
fun <T> rememberState(init: () -> T): Array<T> = TODO()
fun <R> rememberEffect(vararg deps: Any?, block: () -> R) {}
fun <T> rememberRef(init: () -> T): Array<T> = TODO()
fun <T> rememberContext(context: HookContext<T>): T = TODO()
fun <T> HookContext<T>.Provider(value: T, content: () -> Unit) {}
