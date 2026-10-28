@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package fixture
import androidx.compose.runtime.Composable
import android.os.SystemClock
import compose.hooks.query.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

@Composable
fun more(n: Int) {
    cache("a${SystemClock.elapsedRealtime()}") { 1 }                       // E3 error
    cache("b${Instant.now()}") { 1 }                                        // E3 error
    cache("c${ThreadLocalRandom.current().nextInt()}") { 1 }                // E3 error
    cache("d${TimeSource.Monotonic.markNow()}") { 1 }                       // E3 error
    cache("e${Uuid.random()}") { 1 }                                        // E3 error
    cache("f${(1..9).random()}") { 1 }                                      // E3 error
    cache("g${"abc".random()}") { 1 }                                       // E3 error
    cache("h${StrictMath.random()}") { 1 }                                  // E3 error
    cache("i${Random.Default.nextLong()}") { 1 }                            // E3 error
    cachedValue("v${System.nanoTime()}") { 1 }                              // E3 error
    // stable members of the same owners / packages
    cache("j${System.getProperty("x")}") { 1 }
    cache("k${Math.max(n, 1)}") { 1 }
    cache("l${UUID.fromString("00000000-0000-0000-0000-000000000000")}") { 1 }
    cache("m${Random(42).hashCode()}") { 1 }
    cache("o${listOf(n).first()}${(1..n).first}${"ab".first()}") { 1 }
    cache("p${LocalDate.of(2026, 1, 1)}") { 1 }
    cache("q${SystemClock.sleep(1)}") { 1 }
    cache("r${TimeSource.Monotonic}") { 1 }
    cache("s${n.toString()}") { 1 }
    shareState(id = "t$n") { 0 }
    cachedValue("u$n") { 1 }
}
