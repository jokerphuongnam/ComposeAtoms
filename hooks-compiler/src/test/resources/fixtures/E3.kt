package fixture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import compose.hooks.*
import compose.hooks.query.*
import java.util.UUID
import kotlin.random.Random

val top = cache("boot-${System.currentTimeMillis()}") { 1 }          // ok: evaluated once

@Composable
fun screen(id: Int) {
    cache("feed-${System.currentTimeMillis()}") { 1 }                 // E3 error
    cache(key = "n" + System.nanoTime()) { 1 }                         // E3 error
    shareState(UUID.randomUUID().toString()) { 0 }                     // E3 error
    rememberShareState("r${Random.nextInt()}") { 0 }                   // E3 error
    cache("list-${listOf(1, 2).random()}") { 1 }                       // E3 error
    cache("user-$id") { 1 }                                            // ok: stable parameter
    val stable = remember { "s${System.nanoTime()}" }
    cache(stable) { 1 }                                                // ok: remembered once
    cache("ok") { "v${System.nanoTime()}" }                            // ok: fetch body, not the key
    val onClick = { cache("click-${System.nanoTime()}") { 1 } }        // ok: event callback
    onClick()
}

fun hook() = customRemember {
    key(1) { cache("h-${Math.random()}") { 1 } }                       // E3 error (key() body runs with the hook)
    rememberEffect { cache("e-${System.nanoTime()}") { 1 } }           // ok: effect body
    Unit
}

fun plain() = cache("plain-${System.nanoTime()}") { 1 }                // ok: not a hook body

@Composable
fun stableAlternative(key: String = "x") = cache(key) { 1 }            // ok
