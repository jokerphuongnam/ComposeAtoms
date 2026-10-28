package compose.hooks.example

import compose.hooks.query.QueryClientProvider
import compose.hooks.query.cacheState
import compose.hooks.query.test.runQueryTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The example's hooks under hooks-test / query-test: no device, virtual time, a fresh QueryClient per test. */
class ExampleHooksTest {
    @Test fun profileFormFetchesOnVirtualTime() = runQueryTest {
        val before = api.calls
        val form = renderHook { rememberProfileForm() }
        assertTrue(form.current.loading)
        advanceTimeBy(300)
        assertFalse(form.current.loading)
        assertEquals("Grace", form.current.name)
        assertEquals(before + 1, api.calls)
    }

    @Test fun aFakedProfileNeverHitsTheApi() = runQueryTest {
        fakeCache("profile", Profile("Test user"))
        val before = api.calls
        val form = renderHook { rememberProfileForm() }
        assertEquals("Test user", form.current.name)
        form.act { form.current.onLogin("Bob") }
        assertEquals("Bob", form.current.session.name)
        assertEquals(before, api.calls)
    }

    @Test fun feedStartsFromTheSeededOfflinePageThenRefreshesWhenStale() = runQueryTest {
        val entry = backStackEntry()
        val before = feedApi.calls
        // the screen's client, then the screen's hook under it (same back-stack entry, like FeedScreen)
        val screenClient = renderHook(entry) { rememberFeedClient() }.current
        val feed = renderHook(entry, wrapper = { content -> QueryClientProvider(screenClient) { content() } }) {
            rememberFeed()
        }
        assertNotSame("the screen owns its own client", client, screenClient)
        assertEquals(OFFLINE_FIRST_PAGE, feed.current.posts)
        assertEquals("seeded: no fetch", before, feedApi.calls)

        feed.act { feed.current.onNext() }
        assertEquals(1, feed.current.page)
        assertTrue("page 1 is not cached yet", feed.current.posts.isEmpty())
        advanceTimeBy(500)
        assertEquals(listOf("Post 3", "Post 4", "Post 5"), feed.current.posts.map { it.title })

        entry.pop()
        assertTrue("popping the screen clears its client", screenClient.cacheState<List<Post>>("feed-1").isPending)
        assertEquals(0, entry.hookScopeCount)
    }
}
