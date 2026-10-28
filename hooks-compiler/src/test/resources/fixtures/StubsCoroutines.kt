@file:Suppress("UNUSED_PARAMETER")
package kotlinx.coroutines
object GlobalScope
interface CoroutineScope
fun GlobalScope.launch(block: () -> Unit) {}
fun GlobalScope.async(block: () -> Unit) {}
fun CoroutineScope.launch(block: () -> Unit) {}
