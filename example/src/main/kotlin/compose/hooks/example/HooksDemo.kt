package compose.hooks.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import compose.hooks.Provider
import compose.hooks.customRemember
import compose.hooks.rememberContextOf
import compose.hooks.rememberContext
import compose.hooks.rememberEffect
import compose.hooks.rememberFlow
import compose.hooks.rememberId
import compose.hooks.rememberMemo
import compose.hooks.rememberReducer
import compose.hooks.rememberState
import compose.hooks.query.ParentState
import compose.hooks.query.rememberParentState
import compose.hooks.query.rememberShareState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

data class Theme(val name: String)
data class Wizard(val title: String)

val LocalTheme = rememberContextOf<Theme>("Theme") { Theme("default") }

object DemoLog {
    val lines = ArrayList<String>()
}

class Counter(val count: Int, val inc: () -> Unit, val dec: () -> Unit)

@Composable
fun rememberCounter(): Counter = customRemember {
    val (count, dispatch) = rememberReducer<Int, Int>({ s, a -> s + a }) { 0 }
    Counter(count, { dispatch(1) }, { dispatch(-1) })
}

@Composable
fun rememberTicker(running: Boolean): Int = customRemember {
    var ticks by rememberState { 0 }
    rememberEffect(running) {
        DemoLog.lines += "ticker start running=$running"
        if (running) launch { while (true) { delay(1000); ticks++ } }
        val cleanup = { DemoLog.lines += "ticker cleanup running=$running" }
        cleanup
    }
    ticks
}

@Composable
fun HooksDemoScreen(onBack: () -> Unit) {
    val counter = rememberCounter()
    var running by rememberState { true }
    val ticks = rememberTicker(running)
    val square = rememberMemo(counter.count) { counter.count * counter.count }
    val id = rememberId()
    val clock by rememberFlow(flow { var i = 0; while (true) { emit(i++); delay(500) } }, initial = -1)
    val note = rememberShareState("note") { Note("") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("id=$id", Modifier.testTag("id"))
        Text("count=${counter.count} square=$square", Modifier.testTag("count"))
        Button(onClick = counter.inc, Modifier.testTag("inc")) { Text("+1") }
        Button(onClick = counter.dec) { Text("-1") }
        Text("ticks=$ticks running=$running flow=$clock")
        Button(onClick = { running = !running }) { Text("Toggle ticker") }
        Text("Shared note (same id as profile): ${note.value.text}", Modifier.testTag("demoNote"))
        LocalTheme.Provider(Theme("dark")) { ThemedLabel() }
        ParentState(Wizard("Step 1")) { WizardLabel() }
        Button(onClick = onBack, Modifier.testTag("back")) { Text("Back") }
    }
}

@Composable
private fun ThemedLabel() {
    val theme = rememberContext(LocalTheme)
    Text("theme=${theme.name}", Modifier.testTag("theme"))
}

@Composable
private fun WizardLabel() {
    val wizard = rememberParentState<Wizard>()
    Text("wizard=${wizard.title}", Modifier.testTag("wizard"))
}
