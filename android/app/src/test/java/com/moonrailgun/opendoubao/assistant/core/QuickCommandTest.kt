package com.moonrailgun.opendoubao.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 2026-10-15: "打开飞书" took 31.8 s, of which Feishu was open after 5.2 s; plain commands now skip the models. */
class QuickCommandTest {
    private val apps = mapOf("com.ss.android.lark" to "飞书", "com.android.settings" to "设置",
        "com.google.android.apps.maps" to "地图", "com.autonavi.minimap" to "高德地图", "com.android.chrome" to "Chrome")

    @Test fun anAppNamedExactlyOnceOpensIt() {
        assertEquals(Quick.OpenApp("com.ss.android.lark", "飞书"), QuickCommand.parse("打开飞书", apps))
        assertEquals(Quick.OpenApp("com.ss.android.lark", "飞书"), QuickCommand.parse("帮我打开一下飞书。", apps))
        assertEquals(Quick.OpenApp("com.android.chrome", "Chrome"), QuickCommand.parse("启动 chrome", apps))
        assertEquals(Quick.OpenApp("com.android.settings", "设置"), QuickCommand.parse("打开设置", apps))
        assertEquals(Quick.OpenApp("com.google.android.apps.maps", "地图"), QuickCommand.parse("打开地图", apps))
    }

    @Test fun anythingMoreThanTheAppGoesTheFullWay() {
        assertNull(QuickCommand.parse("打开飞书搜一下孙淼", apps))
        assertNull(QuickCommand.parse("打开和孙淼的聊天窗口", apps))
        // A misheard command ("打开飞书" as "海飞书") names no app.
        assertNull(QuickCommand.parse("海飞书", apps))
        assertNull(QuickCommand.parse("打开微信", apps))
        // "打开蓝牙" switches Bluetooth on; only "打开蓝牙设置" is a page.
        assertNull(QuickCommand.parse("打开蓝牙", apps))
    }

    @Test fun homeAndBack() {
        assertEquals(Quick.Home, QuickCommand.parse("回到桌面", apps))
        assertEquals(Quick.Home, QuickCommand.parse("请返回主屏幕", apps))
        assertEquals(Quick.Back, QuickCommand.parse("返回上一页", apps))
        assertEquals(Quick.Back, QuickCommand.parse("返回", apps))
    }

    @Test fun settingsPagesOpenDirectly() {
        assertEquals(Quick.SettingsPage("android.settings.BLUETOOTH_SETTINGS", "蓝牙"), QuickCommand.parse("打开蓝牙设置", apps))
        assertEquals(Quick.SettingsPage("android.settings.WIFI_SETTINGS", "WLAN"), QuickCommand.parse("打开wifi设置", apps))
        assertEquals(Quick.SettingsPage("android.intent.action.POWER_USAGE_SUMMARY", "电池"), QuickCommand.parse("进入电池设置页面", apps))
        assertEquals(Quick.SettingsPage("android.settings.DISPLAY_SETTINGS", "显示"), QuickCommand.parse("打开亮度设置", apps))
        assertNull(QuickCommand.parse("打开键盘设置", apps))
    }

    @Test fun batteryAndStorageAreReadOnThePhone() {
        listOf("查一下电池电量", "电量", "还剩多少电", "现在电量多少", "查看手机电量").forEach {
            assertEquals(it, Quick.Battery, QuickCommand.parse(it, apps))
        }
        listOf("存储空间还剩多少", "手机还有多少空间", "查一下存储").forEach {
            assertEquals(it, Quick.Storage, QuickCommand.parse(it, apps))
        }
        assertNull(QuickCommand.parse("把电量百分比显示在状态栏", apps))
    }

    @Test fun answersAreFixedSentences() {
        assertEquals("电量 97%，正在充电", QuickCommand.batteryText(97, charging = true))
        assertEquals("电量 41%", QuickCommand.batteryText(41, charging = false))
        assertEquals("存储剩余 85.3 GB，共 128.0 GB", QuickCommand.storageText(85_300_000_000, 128_000_000_000))
    }
}
