@file:Suppress("UNUSED_PARAMETER")
package androidx.compose.runtime
fun <T> key(vararg keys: Any?, block: () -> T): T = block()
fun <T> remember(calculation: () -> T): T = calculation()
