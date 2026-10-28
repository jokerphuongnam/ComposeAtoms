@file:Suppress("UNUSED_PARAMETER")
package compose.hooks.query
fun <T> rememberAtom(atom: T): T = atom
fun <T : Any> rememberParentState(): T = TODO()
fun <T> rememberGlobalState(init: () -> T): T = init()
fun <T : Any> ParentState(value: T, content: () -> Unit) {}
