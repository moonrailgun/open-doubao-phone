package com.openodoubao.assistant.core

enum class Permission { ALLOW, REJECT }

object ActionPolicy {
    fun assess(action: Action, screen: Screen, apps: Set<String>): Permission {
        if (runCatching { action.validate() }.isFailure || screen.protected) return Permission.REJECT
        if (action is Action.OpenApp) return if (action.packageName in apps) Permission.ALLOW else Permission.REJECT
        if (screen.packageName !in apps) return Permission.REJECT
        if (action is Action.OpenUrl) return if (action.packageName in apps) Permission.ALLOW else Permission.REJECT
        val node = when (action) {
            is Action.Click -> screen.elements.find { it.id == action.node && it.clickable }
            is Action.Type -> screen.elements.find { it.id == action.node && it.editable }
            is Action.Scroll -> screen.elements.find { it.id == action.node && it.scrollable }
            else -> null
        }
        if ((action is Action.Click || action is Action.Type || action is Action.Scroll) &&
            (node == null || !node.enabled || node.password)) return Permission.REJECT
        val bounds = screen.windowBounds
        if (action is Action.Tap && !bounds.contains(action.x, action.y)) return Permission.REJECT
        if (action is Action.Swipe && (!bounds.contains(action.x1, action.y1) || !bounds.contains(action.x2, action.y2))) return Permission.REJECT
        return Permission.ALLOW
    }
}

// Conservatively include the entire swipe rectangle when checking overlays.
fun Action.gestureBounds(): Bounds? = when (this) {
    is Action.Tap -> Bounds(x, y, x + 1, y + 1)
    is Action.Swipe -> Bounds(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2) + 1, maxOf(y1, y2) + 1)
    else -> null
}

object Candidates {
    fun build(screen: Screen, plan: Plan, apps: Set<String>, launchableApps: Set<String> = apps): List<Candidate> {
        val actions = mutableListOf<Action>()
        screen.elements.filter { it.enabled && !it.password }.forEach { node ->
            if (node.clickable) actions += Action.Click(node.id)
            if (node.editable) plan.texts.forEach { actions += Action.Type(node.id, it) }
            if (node.scrollable) { actions += Action.Scroll(node.id, true); actions += Action.Scroll(node.id, false) }
        }
        actions += Action.Back
        actions += Action.Wait
        apps.filter { it != screen.packageName && it in launchableApps }.forEach { actions += Action.OpenApp(it) }
        if (plan.app in launchableApps) plan.urls.forEach { actions += Action.OpenUrl(it, plan.app) }
        return actions.filter { ActionPolicy.assess(it, screen, apps) != Permission.REJECT }
            .distinct().take(200).mapIndexed { i, a -> Candidate("c$i", a, a.description(screen)) }
    }
}
