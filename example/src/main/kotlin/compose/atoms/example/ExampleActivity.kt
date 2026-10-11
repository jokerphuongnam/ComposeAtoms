package compose.atoms.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.atoms.ParentState
import compose.atoms.cache
import compose.atoms.globalState
import compose.atoms.rememberCache
import compose.atoms.rememberGlobalState
import compose.atoms.rememberParentState
import compose.atoms.rememberShareState
import kotlin.time.Duration.Companion.seconds

data class Session(val name: String)

data class Note(val text: String)

data class Wizard(val title: String)

private val session = globalState { Session("Ada") }
private val fact = cache<String>("fact", staleTime = 60.seconds) { "A prime" }

class ExampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ExampleScreen() } }
    }
}

@Composable
private fun ExampleScreen() {
    val sessionState = rememberGlobalState { Session("Ada") }
    val note = rememberShareState("note") { Note("Shared note") }
    val wizard = remember { mutableStateOf(Wizard("Wizard")) }
    val cached = rememberCache(fact)
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Global  ${sessionState.value.name}")
        Button(onClick = { session.write(Session("Grace")) }) {
            Text("Rename global")
        }
        GlobalReader()

        Text("Share  ${note.value.text}")
        Button(onClick = { note.update { it.copy(text = it.text + " +") } }) {
            Text("Append share")
        }
        ShareReader()

        Text("Parent  ${wizard.value.title}")
        Button(onClick = { wizard.value = Wizard("Renamed") }) {
            Text("Retitle parent")
        }
        ParentState(wizard.value) {
            ParentChild()
        }

        Text("Cache  ${cached.data ?: if (cached.isFetching) "loading" else "empty"}")
        Button(onClick = { fact.refetch() }) {
            Text("Refetch cache")
        }
        CacheReader()
    }
}

@Composable
private fun GlobalReader() {
    val session = rememberGlobalState { Session("unused") }
    Text("Global reader  ${session.value.name}")
}

@Composable
private fun ShareReader() {
    val note = rememberShareState("note") { Note("unused") }
    Text("Share reader  ${note.value.text}")
}

@Composable
private fun ParentChild() {
    val wizard = rememberParentState<Wizard>()
    Text("Child  ${wizard.title}")
}

@Composable
private fun CacheReader() {
    val fact = rememberCache(fact)
    Text("Cache reader  ${fact.data ?: "empty"}")
}
