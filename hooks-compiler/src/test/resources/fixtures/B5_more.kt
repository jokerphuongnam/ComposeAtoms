package fixture.b5
import compose.hooks.*
import compose.hooks.query.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

private val Session = rememberContextOf<StringBuilder>("Session2") { StringBuilder() }
val topLevel = StringBuilder()
object MyScope
fun MyScope.launch(block: () -> Unit) {}
class Thread(val block: () -> Unit)

fun b5more(scope: CoroutineScope) = customRemember {
    val session = rememberContext(Session)
    val parent = rememberParentState<StringBuilder>()
    val plainLocal = StringBuilder()
    val number = 1
    val work: () -> Unit = { println(session) }
    GlobalScope.async { println(parent) }                  // B5 warning (async, rememberParentState)
    thread(name = "t") { println(session) }               // B5 warning (thread with named args)
    scope.launch { println(session) }                      // ok: a scope that is not GlobalScope
    MyScope.launch { println(session) }                    // ok: not kotlinx.coroutines
    with(GlobalScope) { launch { println(number) } }       // ok: implicit receiver, plain local
    GlobalScope.launch { println(plainLocal); println(topLevel); println(number) } // ok: not lent values
    GlobalScope.launch(work)                               // ok: not a lambda literal
    Thread { println(session) }                            // ok: not java.lang.Thread
}
