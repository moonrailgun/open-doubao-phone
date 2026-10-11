package com.moonrailgun.opendoubao.assistant.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
    fun intersects(other: Bounds) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

data class Element(
    val id: String, val text: String, val description: String, val className: String,
    val resourceId: String, val bounds: Bounds, val clickable: Boolean = false,
    val editable: Boolean = false, val scrollable: Boolean = false,
    val enabled: Boolean = true, val password: Boolean = false,
    val checked: Boolean = false,
) {
    val label: String get() = listOf(text, description, resourceId.substringAfterLast('/')).filter { it.isNotBlank() }.distinct().joinToString(" · ").take(240)
    fun json() = JSONObject().put("id", id).put("text", if (password) "[redacted]" else text)
        .put("description", if (password) "" else description).put("class", className).put("resource", resourceId)
        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        .put("clickable", clickable).put("editable", editable).put("scrollable", scrollable)
        .put("enabled", enabled).put("checked", checked)
}

data class Screen(val packageName: String, val windowId: Int, val width: Int, val height: Int,
    val rotation: Int, val elements: List<Element>, val truncated: Boolean = false,
    val windowBounds: Bounds = Bounds(0, 0, width, height)) {
    val protected: Boolean get() = elements.any { it.password }
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest(toString().toByteArray()).joinToString("") { "%02x".format(it) }
    fun json() = JSONObject().put("app", packageName).put("window", windowId)
        .put("width", width).put("height", height).put("rotation", rotation).put("truncated", truncated)
        .put("window_bounds", JSONArray(listOf(windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom)))
        .put("elements", JSONArray(elements.filterNot { it.password }.map { it.json() }))
}

sealed interface Action {
    data class OpenApp(val packageName: String) : Action
    data class OpenUrl(val url: String, val packageName: String) : Action
    data class Click(val node: String) : Action
    data class Type(val node: String, val text: String) : Action
    data class Scroll(val node: String, val forward: Boolean) : Action
    data class Tap(val x: Int, val y: Int) : Action
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : Action
    data object Back : Action
    data object Home : Action
    data object Wait : Action

    fun validate() {
        when (this) {
            is OpenApp -> require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+"))) { "无效应用包名" }
            is OpenUrl -> {
                OpenApp(packageName).validate()
                val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("无效网址") }
                require(url.length <= 4096 && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "只允许不含凭据的 HTTPS 网址" }
            }
            is Click -> requireNode(node)
            is Type -> { requireNode(node); require(text.length in 1..8000) { "输入文字长度无效" } }
            is Scroll -> requireNode(node)
            is Tap -> require(x >= 0 && y >= 0) { "坐标越界" }
            is Swipe -> require(listOf(x1, y1, x2, y2).all { it >= 0 }) { "坐标越界" }
            else -> Unit
        }
    }

    fun json(): JSONObject = JSONObject().apply {
        when (val a = this@Action) {
            is OpenApp -> put("action", "open_app").put("package", a.packageName)
            is OpenUrl -> put("action", "open_url").put("package", a.packageName).put("url", a.url)
            is Click -> put("action", "click").put("node", a.node)
            is Type -> put("action", "type").put("node", a.node).put("text", a.text)
            is Scroll -> put("action", "scroll").put("node", a.node).put("forward", a.forward)
            is Tap -> put("action", "tap").put("x", a.x).put("y", a.y)
            is Swipe -> put("action", "swipe").put("x1", a.x1).put("y1", a.y1).put("x2", a.x2).put("y2", a.y2)
            Back -> put("action", "back")
            Home -> put("action", "home")
            Wait -> put("action", "wait")
        }
    }

    fun description(screen: Screen?, complete: Boolean = false): String {
        fun label(id: String) = screen?.elements?.find { it.id == id }?.label ?: id
        return when (this) {
            is OpenApp -> "打开 $packageName"
            is OpenUrl -> "浏览 $url"
            is Click -> "点击「${label(node)}」"
            is Type -> "填写「${label(node)}」：${if (complete) text else text.take(160)}"
            is Scroll -> "${if (forward) "向下" else "向上"}滚动「${label(node)}」"
            is Tap -> "点击坐标 ($x, $y)"
            is Swipe -> "滑动 ($x1, $y1) → ($x2, $y2)"
            Back -> "返回"
            Home -> "回到桌面"
            Wait -> "等待页面变化"
        }
    }
}

private fun requireNode(id: String) = require(id.length <= 200 && id.matches(Regex("0(\\.[0-9]+)*"))) { "无效节点引用" }
data class Candidate(val id: String, val action: Action, val description: String)
/** A non-null [reply] answers the goal without touching the phone; [app] and [steps] are then empty. */
data class Plan(val app: String, val steps: List<String>, val texts: List<String>, val urls: List<String> = emptyList(),
    val memory: List<MemoryOp> = emptyList(), val reply: String? = null)
enum class Effect { YES, NO, UNKNOWN }
data class Previous(val screen: Screen, val action: Action, val dispatched: Boolean)
data class Decision(val action: Action?, val done: Boolean, val effect: Effect)
data class ModelStep(val action: Action? = null, val done: Boolean = false, val handover: String? = null,
    val observations: String = "")
data class Source(val app: String, val text: String, val visual: Boolean = false) {
    fun json() = JSONObject().put("app", app).put("text", text).put("visual_model_observation", visual)
}
data class Evidence(val screens: List<Screen>, val sources: List<Source> = emptyList()) {
    fun json() = JSONObject().put("observations", JSONArray(screens.map { it.json() }))
        .put("retained_sources", JSONArray(sources.map { it.json() }))
}

class ScreenChangedException : IllegalStateException("页面已变化")
class PhoneUnavailableException(message: String) : IllegalStateException(message)

interface Phone {
    /** Where actions land, written to the task log once at the start. */
    val mode: String get() = "前台"
    fun canLaunch(packageName: String): Boolean
    suspend fun observe(): Screen
    suspend fun screenshot(screen: Screen): String
    suspend fun execute(action: Action, expected: Screen?, expectedImage: String? = null): Boolean
}

interface Models {
    suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String> = apps): Plan
    suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision
    suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep
    suspend fun summarize(goal: String, evidence: Evidence): String
}
