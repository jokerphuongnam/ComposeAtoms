package compose.hooks.example

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ExampleActivity>()

    @Test fun profileScreenUsesBothLayers() {
        rule.waitUntil(5000) { rule.onAllNodesWithTextCount("Profile loaded") == 1 }
        rule.onNodeWithTag("session").assertTextEquals("Session: Ada")
        rule.waitUntil(3000) { rule.onAllNodesWithTextCount("Grace") >= 1 }
        val calls = api.calls
        rule.onNodeWithText("Use name as session").performClick()
        rule.onNodeWithTag("session").assertTextEquals("Session: Grace")
        rule.onNodeWithTag("appendNote").performClick()
        rule.onNodeWithTag("note").assertTextEquals("Note: +")
        // second screen shares the note by id, core hooks + context + ParentState
        rule.onNodeWithTag("openHooks").performClick()
        rule.onNodeWithTag("demoNote").assertTextEquals("Shared note (same id as profile): +")
        rule.onNodeWithTag("theme").assertTextEquals("theme=dark")
        rule.onNodeWithTag("wizard").assertTextEquals("wizard=Step 1")
        rule.onNodeWithTag("inc").performClick()
        rule.onNodeWithTag("count").assertTextEquals("count=1 square=1")
        rule.onNodeWithTag("back").performClick()
        // back: form state kept, no refetch within staleTime
        rule.onNodeWithTag("note").assertTextEquals("Note: +")
        rule.onNodeWithTag("session").assertTextEquals("Session: Grace")
        assertEquals(calls, api.calls)
        // re-open demo: popped screen started fresh
        rule.onNodeWithTag("openHooks").performClick()
        rule.onNodeWithTag("count").assertTextEquals("count=0 square=0")
    }

    @Test fun recreateKeepsProfileForm() {
        rule.waitUntil(5000) { rule.onAllNodesWithTextCount("Profile loaded") == 1 }
        rule.onNodeWithTag("appendNote").performClick()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag("note").assertTextEquals("Note: +")
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
