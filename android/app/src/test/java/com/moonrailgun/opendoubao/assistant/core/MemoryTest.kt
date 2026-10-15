package com.moonrailgun.opendoubao.assistant.core

import org.junit.Assert.*
import org.junit.Test

class MemoryTest {
    private var next = 0
    private fun apply(memory: List<Memory>, vararg ops: MemoryOp) = MemoryOps.apply(memory, ops.toList(), now = 9) { "n${next++}" }

    @Test fun editsApplyInOrderAndSkipWhatTheyCannotApply() {
        val start = listOf(Memory("a", "住在杭州", 1), Memory("b", "常用 Chrome", 1))
        val update = apply(start, MemoryOp.Add("  车停在 B2-17  "), MemoryOp.Update("a", "住在上海"), MemoryOp.Delete("b"),
            MemoryOp.Delete("missing"), MemoryOp.Update("a", " "), MemoryOp.Add("住在上海"), MemoryOp.Add("x".repeat(300)))
        assertEquals(listOf(Memory("a", "住在上海", 9), Memory("n0", "车停在 B2-17", 9), Memory("n1", "x".repeat(MemoryOps.LENGTH), 9)), update.memory)
        assertEquals("新增 2 条，修改 1 条，删除 1 条，忽略 3 条（无效、重复或已满 50 条）", update.summary)
        assertEquals("无变化", apply(start).summary)
    }

    @Test fun addsPastTheLimitAreDroppedInsteadOfOlderMemory() {
        val full = (0 until MemoryOps.LIMIT).map { Memory("m$it", "fact $it", 1) }
        val update = apply(full, MemoryOp.Add("one more"))
        assertEquals(full, update.memory)
        assertEquals(MemoryOps.LIMIT, apply(full, MemoryOp.Delete("m0"), MemoryOp.Add("one more")).memory.size)
    }

    @Test fun defaultsAreSeededOnceSoADeletedOneStaysGone() {
        val ids = DefaultMemory.ALL.map { it.id }.toSet()
        assertEquals(DefaultMemory.ALL.map { it.copy(updated = 5) } to ids, DefaultMemory.seed(emptyList(), emptySet(), now = 5))
        assertEquals(emptyList<Memory>() to ids, DefaultMemory.seed(emptyList(), ids))
        val full = (0 until MemoryOps.LIMIT).map { Memory("m$it", "fact $it", 1) }
        assertEquals(full to emptySet<String>(), DefaultMemory.seed(full, emptySet()))
        val typed = DefaultMemory.ALL.map { Memory("u-${it.id}", it.text, 1) }
        assertEquals(typed to ids, DefaultMemory.seed(typed, emptySet()))
    }

    @Test fun planCarriesMemoryEditsAndADirectReply() {
        val reply = ModelProtocol.parsePlan("""{"reply":"记住了","memory":[{"op":"add","text":"车停在 B2-17"},
            {"op":"delete","id":"a"},{"op":"update","id":"b"},{"op":"forget","id":"c"},"junk",{"op":"add","text":42}]}""", setOf("browser.app"))
        assertEquals("记住了", reply.reply)
        assertEquals(listOf(MemoryOp.Add("车停在 B2-17"), MemoryOp.Delete("a")), reply.memory)
        assertTrue(reply.steps.isEmpty())
        val plan = ModelProtocol.parsePlan("""{"app":"browser.app","steps":["search"],"texts":[],"memory":[{"op":"add","text":"住在杭州"}]}""", setOf("browser.app"))
        assertNull(plan.reply)
        assertEquals(listOf(MemoryOp.Add("住在杭州")), plan.memory)
        assertThrows(IllegalArgumentException::class.java) { ModelProtocol.parsePlan("""{"reply":""}""", setOf("browser.app")) }
    }

    @Test fun reflectionRepliesMustBeJsonWithAMemoryList() {
        assertEquals(listOf(MemoryOp.Update("a", "住在上海")), ModelProtocol.parseMemory("```json\n{\"memory\":[{\"op\":\"update\",\"id\":\"a\",\"text\":\"住在上海\"}]}\n```"))
        assertEquals(emptyList<MemoryOp>(), ModelProtocol.parseMemory("""{"memory":[]}"""))
        listOf("nothing to keep", "{}").forEach { assertThrows(IllegalArgumentException::class.java) { ModelProtocol.parseMemory(it) } }
    }
}
