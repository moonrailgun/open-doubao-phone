package com.moonrailgun.opendoubao.assistant.core

/**
 * Whether the node an element action names is unchanged between the read it was decided on and a fresh read of the
 * same window. Execution finds the node by its path, so the same path must hold an identical element (text, kind,
 * resource id, bounds and state); the rest of the page may have changed, such as a chat list receiving messages.
 */
internal fun sameTarget(action: Action?, before: Screen, after: Screen): Boolean {
    val node = when (action) {
        is Action.Click -> action.node
        is Action.Type -> action.node
        is Action.Scroll -> action.node
        is Action.Submit -> action.node
        else -> return false
    }
    if (after.packageName != before.packageName || after.windowId != before.windowId) return false
    val target = before.elements.firstOrNull { it.id == node } ?: return false
    return after.elements.firstOrNull { it.id == node } == target
}
