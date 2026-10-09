package com.openodoubao.assistant.core

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    private val allowed = setOf("browser.app", "notes.app")

    @Test fun unselectedAppsAndStaleNodesCannotExecute() {
        assertEquals(Permission.REJECT, ActionPolicy.assess(Action.OpenApp("bank.app"), screen(), allowed))
        assertEquals(Permission.REJECT, ActionPolicy.assess(Action.Click("missing"), screen(), allowed))
        assertEquals(Permission.REJECT, ActionPolicy.assess(Action.Click("0.1"), screen("bank.app"), allowed))
    }

    @Test fun ordinaryActionsInAllowedAppsExecuteAutomatically() {
        assertEquals(Permission.ALLOW, ActionPolicy.assess(Action.Click("0.1"), screen(), allowed))
        assertEquals(Permission.ALLOW, ActionPolicy.assess(Action.OpenApp("notes.app"), screen(), allowed))
        val editor = screen().copy(elements = listOf(screen().elements[0].copy(editable = true)))
        assertEquals(Permission.ALLOW, ActionPolicy.assess(Action.Type("0.1", "draft"), editor, allowed))
        assertEquals(Permission.ALLOW, ActionPolicy.assess(Action.OpenUrl("https://example.com", "browser.app"), screen(), allowed))
    }

    @Test fun passwordScreensAreNeverAutomatic() {
        val protected = screen().copy(elements = listOf(screen().elements[0].copy(password = true)))
        assertEquals(Permission.REJECT, ActionPolicy.assess(Action.Click("0.1"), protected, allowed))
    }

    @Test fun snapshotFingerprintChangesWhenContentOrWindowChanges() {
        assertNotEquals(screen().fingerprint, screen(text = "Buy").fingerprint)
        assertNotEquals(screen().fingerprint, screen().copy(windowId = 8).fingerprint)
    }

    @Test fun outOfBoundsAndJavascriptUrlsAreRejected() {
        assertEquals(Permission.REJECT, ActionPolicy.assess(Action.Tap(1080, 2), screen(), allowed))
        assertThrows(IllegalArgumentException::class.java) { Action.OpenUrl("javascript:alert(1)", "browser.app").validate() }
        assertThrows(IllegalArgumentException::class.java) { Action.OpenUrl("https://user:pass@example.com", "browser.app").validate() }
    }

    @Test fun candidatesDoNotInventTextOrExposePasswordFields() {
        val s = screen().copy(elements = listOf(screen().elements[0].copy(editable = true, password = true)))
        val candidates = Candidates.build(s, Plan("browser.app", listOf("search"), listOf("cats")), allowed)
        assertFalse(candidates.any { it.action is Action.Type || it.action is Action.Click })
    }

    @Test fun selectedComponentsKeepLocalActionsWithoutInventingLaunchOrUrlActions() {
        val component = "com.google.android.gms"
        val apps = allowed + component
        val plan = Plan(component, emptyList(), listOf("query"), listOf("https://example.com"))
        val current = screen(component).copy(elements = listOf(screen().elements[0].copy(editable = true)))
        val actions = Candidates.build(current, plan, apps, allowed).map { it.action }
        assertTrue(Action.Click("0.1") in actions)
        assertTrue(Action.Type("0.1", "query") in actions)
        assertTrue(Action.OpenApp("browser.app") in actions)
        assertFalse(actions.any { it is Action.OpenUrl })
        val otherPage = Candidates.build(screen(), plan, apps, allowed).map { it.action }
        assertFalse(Action.OpenApp(component) in otherPage)
        assertTrue(Action.OpenApp("notes.app") in otherPage)
    }

    @Test fun launchableAppsStillRequireTaskAuthorization() {
        val actions = Candidates.build(screen(), Plan("browser.app", emptyList(), emptyList()),
            allowed, allowed + "unselected.app").map { it.action }
        assertFalse(Action.OpenApp("unselected.app") in actions)
    }

    @Test fun coordinatesStayInsideTheSelectedWindowInSplitScreen() {
        val selected = screen().copy(windowBounds = Bounds(0, 100, 540, 900))
        assertEquals(Permission.REJECT, ActionPolicy.assess(Action.Tap(600, 300), selected, allowed))
        assertEquals(Permission.REJECT, ActionPolicy.assess(Action.Swipe(200, 300, 600, 300), selected, allowed))
        assertEquals(Permission.ALLOW, ActionPolicy.assess(Action.Tap(300, 300), selected, allowed))
        assertNotEquals(screen().fingerprint, selected.fingerprint)
    }

    @Test fun actionDescriptionsCanIncludeCompleteTextAndUrl() {
        val content = "draft ".repeat(500) + "END OF EXACT CONTENT"
        assertTrue(Action.Type("0.1", content).description(screen(), complete = true).endsWith(content))
        val url = "https://example.com/" + "x".repeat(500)
        assertTrue(Action.OpenUrl(url, "browser.app").description(screen(), complete = true).contains(url))
    }
}
