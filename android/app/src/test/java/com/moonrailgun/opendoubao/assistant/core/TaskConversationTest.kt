package com.moonrailgun.opendoubao.assistant.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskConversationTest {
    @Test fun followUpKeepsGoalsResultsAndInterruptedProgressInOrder() {
        val conversation = TaskConversation()
        conversation.begin("1", "查明天去杭州的车票")
        conversation.update("1", TaskState(status = TaskStatus.COMPLETED, goal = "查明天去杭州的车票", result = "找到三班车"))
        conversation.begin("2", "选下午那班")
        conversation.update("2", TaskState(status = TaskStatus.PAUSED, goal = "选下午那班", progress = listOf("已打开车次详情")))
        val turns = conversation.json()
        assertEquals("找到三班车", turns.getJSONObject(0).getString("result"))
        assertEquals("选下午那班", turns.getJSONObject(1).getString("goal"))
        assertEquals("STOPPED", turns.getJSONObject(1).getString("status"))
        assertEquals("已打开车次详情", turns.getJSONObject(1).getJSONArray("progress").getString(0))
        turns.getJSONObject(0).put("goal", "mutated")
        assertEquals("查明天去杭州的车票", conversation.json().getJSONObject(0).getString("goal"))
    }

    @Test fun clearingIgnoresLateUpdatesAndStartsAnIndependentConversation() {
        val conversation = TaskConversation()
        conversation.begin("old", "旧要求")
        conversation.clear()
        conversation.update("old", TaskState(status = TaskStatus.COMPLETED, result = "迟到的结果"))
        assertEquals(0, conversation.json().length())
        conversation.begin("new", "新要求")
        assertEquals(1, conversation.json().length())
        assertEquals("新要求", conversation.json().getJSONObject(0).getString("goal"))
    }

    @Test fun historyIsBoundedAndKeepsTheLatestFullGoal() {
        val conversation = TaskConversation()
        repeat(20) { index ->
            conversation.begin("$index", "$index" + "长".repeat(3998))
            conversation.update("$index", TaskState(result = "果".repeat(4000),
                progress = List(30) { "第 ${it + 1} 步：" + "进".repeat(1000) }))
        }
        val history = conversation.json()
        assertTrue(history.length() in 1..8)
        assertTrue(history.toString().toByteArray().size <= 24_000)
        assertEquals("19" + "长".repeat(3998), history.getJSONObject(history.length() - 1).getString("goal"))
        assertTrue(history.getJSONObject(history.length() - 1).getBoolean("truncated"))
        val progress = history.getJSONObject(history.length() - 1).getJSONArray("progress")
        assertEquals(30, progress.length())
        assertTrue(progress.getString(0).startsWith("第 1 步："))
        assertTrue(progress.getString(29).startsWith("第 30 步："))
    }

    @Test fun oversizedConversationFallsBackInsteadOfSilentlyActingWithoutHistory() {
        val context = JSONObject().put("conversation", JSONArray().put(JSONObject().put("goal", "长".repeat(20_000))))
        assertNull(JevProtocol.request("test", "改成明天", screen(), emptyList(), null, context = context))
    }
}
