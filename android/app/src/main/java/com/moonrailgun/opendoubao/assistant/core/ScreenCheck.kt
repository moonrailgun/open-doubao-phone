package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Shared by a resumed run and its screenshot coroutines; cleared after the first successful interaction (not Wait). */
internal class ScreenChangeOverride(var active: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScreenChangeOverride>
}

internal suspend fun Screen.canIgnoreChanges(current: Screen): Boolean =
    currentCoroutineContext()[ScreenChangeOverride]?.active == true &&
        !protected && !current.protected && packageName == current.packageName && windowId == current.windowId &&
        width == current.width && height == current.height && rotation == current.rotation && windowBounds == current.windowBounds

internal suspend fun Screen.matchesForAction(current: Screen): Boolean =
    !protected && !current.protected && (fingerprint == current.fingerprint || canIgnoreChanges(current))
