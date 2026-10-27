package compose.hooks.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import compose.hooks.customRemember
import compose.hooks.rememberEffect
import compose.hooks.rememberState
import compose.hooks.query.cache
import compose.hooks.query.globalState
import compose.hooks.query.rememberCache
import compose.hooks.query.rememberGlobalState
import compose.hooks.query.rememberShareState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

data class Session(val name: String)
data class Profile(val name: String)
data class Note(val text: String)

object api {
    var calls = 0
    suspend fun profile(): Profile {
        calls++
        delay(300)
        return Profile("Grace")
    }
}

private val profileQuery = cache<Profile>("profile", staleTime = 60.seconds) { api.profile() }

class ProfileForm(
    val session: Session,
    val loading: Boolean,
    val name: String,
    val onName: (String) -> Unit,
    val onRefresh: () -> Unit,
    val onLogin: (String) -> Unit,
)

@Composable
fun rememberProfileForm() = customRemember {
    val session by rememberGlobalState { Session("Ada") }
    val profile = rememberCache(profileQuery)
    var name by rememberState("")
    rememberEffect(profile.data) { profile.data?.let { name = it.name } }
    ProfileForm(
        session = session,
        loading = profile.isFetching,
        name = name,
        onName = { name = it },
        onRefresh = { profileQuery.refetch() },
        onLogin = { globalState { Session("Ada") }.write(Session(it)) },
    )
}

@Composable
fun ProfileScreen(onOpenHooks: () -> Unit) {
    val form = rememberProfileForm()
    val note = rememberShareState("note") { Note("") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Session: ${form.session.name}", Modifier.testTag("session"))
        Text(if (form.loading) "Loading profile…" else "Profile loaded", Modifier.testTag("loading"))
        OutlinedTextField(form.name, form.onName, Modifier.testTag("name"), label = { Text("Name") })
        Button(onClick = form.onRefresh) { Text("Refetch profile") }
        Button(onClick = { form.onLogin(form.name) }) { Text("Use name as session") }
        Text("Note: ${note.value.text}", Modifier.testTag("note"))
        Button(onClick = { note.update { it.copy(text = it.text + "+") } }, Modifier.testTag("appendNote")) { Text("Append to shared note") }
        Button(onClick = onOpenHooks, Modifier.testTag("openHooks")) { Text("Open core hooks demo") }
    }
}
