package compose.hooks.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class ExampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ExampleApp() } }
    }
}

@Composable
fun ExampleApp() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "profile") {
        composable("profile") { ProfileScreen(onOpenHooks = { nav.navigate("hooks") }, onOpenFeed = { nav.navigate("feed") }) }
        composable("feed") { FeedScreen(onBack = { nav.popBackStack() }) }
        composable("hooks") { HooksDemoScreen(onBack = { nav.popBackStack() }) }
    }
}
