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
import compose.hooks.customRemember
import compose.hooks.query.LocalQueryClient
import compose.hooks.query.QueryClient
import compose.hooks.query.QueryClientProvider
import compose.hooks.query.cache
import compose.hooks.query.rememberCache
import compose.hooks.query.rememberQueryClient
import compose.hooks.query.seedCache
import compose.hooks.rememberMemo
import compose.hooks.rememberState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

data class Post(val id: Int, val title: String)

object feedApi {
    var calls = 0
    suspend fun page(page: Int): List<Post> {
        calls++
        delay(500)
        return List(3) { Post(page * 3 + it, "Post ${page * 3 + it}") }
    }
}

/** Bundled with the app (an offline snapshot): page 0 shows at once, then refreshes after it goes stale. */
val OFFLINE_FIRST_PAGE = listOf(Post(0, "Post 0 (offline)"), Post(1, "Post 1 (offline)"), Post(2, "Post 2 (offline)"))

/**
 * A client owned by the feed screen: its cache and global states live while the screen is on the back stack and
 * are cleared when it is popped. It runs on the parent client's config (dispatcher + clock), so tests get virtual
 * time for free. Page 0 is seeded from [OFFLINE_FIRST_PAGE], fresh for 5s.
 */
@Composable
fun rememberFeedClient(): QueryClient = customRemember {
    val client = rememberQueryClient(LocalQueryClient.current.config)
    rememberMemo(client) { client.seedCache("feed-0", OFFLINE_FIRST_PAGE, freshFor = 5.seconds) }
    client
}

/** What the feed screen shows. */
class FeedState(
    val page: Int,
    val posts: List<Post>,
    val loading: Boolean,
    val onNext: () -> Unit,
    val onRefresh: () -> Unit,
)

/** One page of the feed from the current [LocalQueryClient] (the screen's client under [FeedScreen]). */
@Composable
fun rememberFeed(): FeedState = customRemember {
    var page by rememberState(0)
    val result = rememberCache(cache("feed-$page", staleTime = 30.seconds) { feedApi.page(page) })
    FeedState(
        page = page,
        posts = result.data.orEmpty(),
        loading = result.isFetching,
        onNext = { page++ },
        onRefresh = { result.refetch() },
    )
}

@Composable
fun FeedScreen(onBack: () -> Unit) {
    QueryClientProvider(rememberFeedClient()) {
        val feed = rememberFeed()
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Page ${feed.page}${if (feed.loading) " (loading…)" else ""}", Modifier.testTag("feedPage"))
            feed.posts.forEach { Text(it.title) }
            Button(onClick = feed.onNext, Modifier.testTag("feedNext")) { Text("Next page") }
            Button(onClick = feed.onRefresh) { Text("Refresh") }
            Button(onClick = onBack, Modifier.testTag("feedBack")) { Text("Back") }
        }
    }
}
