package com.moonrailgun.opendoubao.assistant.core

import java.util.Locale

/** A plain command the phone carries out at once, without planning, judging or summarizing. */
sealed interface Quick {
    data class OpenApp(val packageName: String, val label: String) : Quick
    data object Home : Quick
    data object Back : Quick
    data class SettingsPage(val action: String, val label: String) : Quick
    data object Battery : Quick
    data object Storage : Quick
}

/**
 * Recognizes plain commands entirely on the phone. The whole command must be the verb and its object (polite words
 * aside), and an app must be named exactly once among [parse]'s apps; anything else returns null and takes the full flow.
 */
object QuickCommand {
    private val polite = Regex("^(请|麻烦|帮我|给我|帮忙)+")
    private val home = setOf("回到桌面", "回桌面", "返回桌面", "回到主屏幕", "回主屏幕", "返回主屏幕")
    private val back = setOf("返回", "返回上一页", "回到上一页", "后退", "返回上一级")
    private val settings = Regex("^(打开|进入|去)(系统)?(蓝牙|wlan|wifi|wi-fi|无线网络|无线局域网|网络|电池|显示|亮度|声音|音量|存储|位置|定位|应用)(的)?设置(页面|页)?$")
    private val pages = mapOf(
        "蓝牙" to ("android.settings.BLUETOOTH_SETTINGS" to "蓝牙"),
        "wlan" to ("android.settings.WIFI_SETTINGS" to "WLAN"), "wifi" to ("android.settings.WIFI_SETTINGS" to "WLAN"),
        "wi-fi" to ("android.settings.WIFI_SETTINGS" to "WLAN"), "无线网络" to ("android.settings.WIFI_SETTINGS" to "WLAN"),
        "无线局域网" to ("android.settings.WIFI_SETTINGS" to "WLAN"),
        "网络" to ("android.settings.WIRELESS_SETTINGS" to "网络"),
        "电池" to ("android.intent.action.POWER_USAGE_SUMMARY" to "电池"),
        "显示" to ("android.settings.DISPLAY_SETTINGS" to "显示"), "亮度" to ("android.settings.DISPLAY_SETTINGS" to "显示"),
        "声音" to ("android.settings.SOUND_SETTINGS" to "声音"), "音量" to ("android.settings.SOUND_SETTINGS" to "声音"),
        "存储" to ("android.settings.INTERNAL_STORAGE_SETTINGS" to "存储"),
        "位置" to ("android.settings.LOCATION_SOURCE_SETTINGS" to "位置信息"),
        "定位" to ("android.settings.LOCATION_SOURCE_SETTINGS" to "位置信息"),
        "应用" to ("android.settings.MANAGE_APPLICATIONS_SETTINGS" to "应用"),
    )
    private val battery = listOf(
        Regex("^(查一下|查看|查|看一下|看看|看)?(手机)?(现在|当前|目前)?(的)?(电池)?电量(还)?(剩|有|是)?(多少)?$"),
        Regex("^(手机)?还?(剩|有)多少电量?$"))
    private val storage = listOf(
        Regex("^(查一下|查看|查|看一下|看看|看)?(手机)?(的)?(存储|储存)(空间)?(还)?(剩|有)?(多少)?$"),
        Regex("^(手机)?还?(剩|有)多少(存储|储存)?空间$"))
    private val open = Regex("^(打开一下|打开|启动|进入|开启|运行|开一下)(.+?)(应用|软件|app)?$")

    /** [apps] maps package to label for the apps this task may open. */
    fun parse(goal: String, apps: Map<String, String>): Quick? {
        val text = polite.replace(goal.trim().trimEnd('。', '.', '!', '！', '?', '？', '~', '～').replace(Regex("\\s+"), "")
            .lowercase(Locale.ROOT), "")
        if (text in home) return Quick.Home
        if (text in back) return Quick.Back
        settings.matchEntire(text)?.let { match ->
            val (action, label) = pages.getValue(match.groupValues[3])
            return Quick.SettingsPage(action, label)
        }
        if (battery.any { it.matches(text) }) return Quick.Battery
        if (storage.any { it.matches(text) }) return Quick.Storage
        val name = open.matchEntire(text)?.groupValues?.get(2) ?: return null
        val named = apps.filterValues { it.replace(Regex("\\s+"), "").lowercase(Locale.ROOT) == name }
        return named.entries.singleOrNull()?.let { (pkg, label) -> Quick.OpenApp(pkg, label) }
    }

    fun batteryText(level: Int, charging: Boolean) = "电量 $level%" + if (charging) "，正在充电" else ""

    fun storageText(free: Long, total: Long) =
        String.format(Locale.ROOT, "存储剩余 %.1f GB，共 %.1f GB", free / 1e9, total / 1e9)
}
