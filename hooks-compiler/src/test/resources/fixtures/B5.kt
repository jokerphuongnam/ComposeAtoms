package fixture
import compose.hooks.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

private val Session = hookContextOf<StringBuilder>("Session") { StringBuilder() }

fun b5() = customRemember {
    val session = rememberContext(Session)
    val copy = session.toString()
    GlobalScope.launch { println(session) }   // B5 warning
    thread { println(session) }               // B5 warning
    Thread { println(session) }.start()       // B5 warning
    GlobalScope.launch { println(copy) }      // ok: plain copy
    rememberEffect { println(session) }       // ok: scope-owned
}
