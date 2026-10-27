package compose.hooks

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.ref.WeakReference
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

object Lab {
    val log = ArrayList<String>()
    val states = HashMap<String, MutableState<Int>>()
    val scopes = HashMap<String, RememberScope>()
    var nav: NavHostController? = null
    var jobCancelled: Boolean? = null
    fun reset() { log.clear(); states.clear(); scopes.clear(); nav = null; jobCancelled = null }
}

class Form(val count: MutableState<Int>, val inner: MutableState<Int>)

@Composable
fun rememberInnerForm(tag: String): MutableState<Int> = customRemember {
    val s = rememberState { 0 }
    rememberEffect { Lab.log += "run:inner:$tag"; { Lab.log += "clean:inner:$tag" } }
    Lab.scopes["inner:$tag"] = ScopeStack.peek()!!
    s
}

@Composable
fun rememberForm(tag: String): Form = customRemember {
    val count = rememberState { 0 }
    val inner = rememberInnerForm(tag)
    rememberEffect {
        Lab.log += "run:$tag"
        val job = launch { awaitCancellation() }
        val cleanup = { Lab.jobCancelled = job.isCancelled; Lab.log += "clean:$tag" }
        cleanup
    }
    Lab.scopes[tag] = ScopeStack.peek()!!
    Form(count, inner)
}

@Composable
fun Screen(tag: String) {
    val form = rememberForm(tag)
    Lab.states[tag] = form.count
    Lab.states["inner:$tag"] = form.inner
    Column { BasicText("$tag=${form.count.value}", Modifier.testTag(tag)) }
}

@Composable
fun App() {
    val nav = rememberNavController()
    Lab.nav = nav
    NavHost(nav, startDestination = "a") {
        composable("a") { Screen("a") }
        composable("b") { Screen("b") }
    }
}

@RunWith(AndroidJUnit4::class)
class LifecycleTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<HostActivity>

    @Before fun setUp() {
        Lab.reset()
        HostActivity.content = { App() }
        scenario = ActivityScenario.launch(HostActivity::class.java)
        rule.waitForIdle()
    }

    @After fun tearDown() { scenario.close() }

    private fun onMain(block: () -> Unit) { rule.runOnUiThread(block); rule.waitForIdle() }

    @Test fun backNavigationKeepsStateAndDoesNotRerunEffects() {
        val a = Lab.states["a"]!!
        onMain { a.value = 3 }
        rule.onNodeWithTag("a").assertTextEquals("a=3")
        onMain { Lab.nav!!.navigate("b") }
        onMain { Lab.nav!!.popBackStack() }
        rule.onNodeWithTag("a").assertTextEquals("a=3")
        assertSame(a, Lab.states["a"])
        assertEquals(listOf("run:inner:a", "run:a"), Lab.log.filter { it.startsWith("run") && it.endsWith("a") })
        assertFalse("a not cleaned", Lab.log.any { it.startsWith("clean") && it.endsWith("a") })
        assertEquals("popped b cleaned child-first", listOf("clean:inner:b", "clean:b"), Lab.log.filter { it.startsWith("clean") })
    }

    @Test fun popClearsScopeTreeChildFirstAndJobBeforeCleanup_noLeak() {
        onMain { Lab.nav!!.navigate("b") }
        val bScope = Lab.scopes["b"]!!
        val innerScope = Lab.scopes["inner:b"]!!
        val ref = WeakReference<Any>(Lab.states["b"]!!)
        val innerRef = WeakReference<Any>(Lab.states["inner:b"]!!)
        onMain { Lab.nav!!.popBackStack() }
        rule.waitUntil(3000) { bScope.isCleared }
        assertTrue(innerScope.isCleared)
        assertEquals(listOf("clean:inner:b", "clean:b"), Lab.log.filter { it.startsWith("clean") })
        assertEquals(true, Lab.jobCancelled)
        Lab.states.remove("b"); Lab.states.remove("inner:b"); Lab.scopes.clear()
        var gone = false
        repeat(30) {
            Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(100)
            if (ref.get() == null && innerRef.get() == null) { gone = true; return@repeat }
        }
        assertTrue("state of popped screen collected", gone)
    }

    @Test fun recreateKeepsState() {
        val a = Lab.states["a"]!!
        onMain { a.value = 7 }
        scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag("a").assertTextEquals("a=7")
        assertSame(a, Lab.states["a"])
        assertFalse(Lab.log.any { it.startsWith("clean") })
        assertEquals(2, Lab.log.count { it.startsWith("run") })
    }

    @Test fun rotationKeepsState() {
        val a = Lab.states["a"]!!
        onMain { a.value = 9 }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        Thread.sleep(2000); rule.waitForIdle()
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        Thread.sleep(2000); rule.waitForIdle()
        rule.onNodeWithTag("a").assertTextEquals("a=9")
        assertSame(a, Lab.states["a"])
        assertFalse(Lab.log.any { it.startsWith("clean") })
    }

    @Test fun sameRouteTwiceDistinctState() {
        onMain { Lab.nav!!.navigate("b") }
        val b1 = Lab.states["b"]!!
        onMain { b1.value = 1 }
        onMain { Lab.nav!!.navigate("b") }
        val b2 = Lab.states["b"]!!
        assertNotSame(b1, b2)
        assertEquals(0, b2.value)
        onMain { Lab.nav!!.popBackStack() }
        assertSame(b1, Lab.states["b"])
        assertEquals(1, Lab.states["b"]!!.value)
    }

    @Test fun finishClearsEverything() {
        val aScope = Lab.scopes["a"]!!
        scenario.close()
        rule.waitUntil(3000) { aScope.isCleared }
        assertEquals(listOf("clean:inner:a", "clean:a"), Lab.log.filter { it.startsWith("clean") })
    }
}
