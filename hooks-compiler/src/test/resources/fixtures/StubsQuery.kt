@file:Suppress("UNUSED_PARAMETER")
package compose.hooks.query
fun <T> rememberAtom(atom: T): T = atom
fun <T : Any> rememberParentState(): T = TODO()
fun <T> rememberGlobalState(init: () -> T): T = init()
fun <T : Any> ParentState(value: T, content: () -> Unit) {}
fun <T> cache(key: String, fetch: () -> T): T = fetch()
fun <T> cachedValue(key: String, fetch: () -> T): T = fetch()
fun <T> shareState(id: String, create: () -> T): T = create()
fun <T> rememberShareState(id: String, create: () -> T): T = create()
