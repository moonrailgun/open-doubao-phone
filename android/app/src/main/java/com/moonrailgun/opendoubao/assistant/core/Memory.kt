package com.moonrailgun.opendoubao.assistant.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One long-term fact about the user, shown to the chat model as data, never as instructions. */
data class Memory(val id: String, val text: String, val updated: Long) {
    fun json() = JSONObject().put("id", id).put("text", text)
}

/** A recent completed task, given to the chat model so follow-ups like "that result" resolve. */
data class PastTask(val goal: String, val result: String) {
    fun json() = JSONObject().put("goal", goal).put("result", result)
}

sealed interface MemoryOp {
    data class Add(val text: String) : MemoryOp
    data class Update(val id: String, val text: String) : MemoryOp
    data class Delete(val id: String) : MemoryOp
}

data class MemoryUpdate(val memory: List<Memory>, val summary: String)

object MemoryOps {
    const val LIMIT = 50
    const val LENGTH = 200

    /** Unknown ids and blank text are skipped; adds past [LIMIT] are dropped, never older entries. */
    fun apply(memory: List<Memory>, ops: List<MemoryOp>, now: Long = System.currentTimeMillis(),
              newId: () -> String = { UUID.randomUUID().toString().take(8) }): MemoryUpdate {
        val result = memory.toMutableList()
        var added = 0; var updated = 0; var deleted = 0; var skipped = 0
        fun indexOf(id: String) = result.indexOfFirst { it.id == id }
        ops.forEach { op ->
            when (op) {
                is MemoryOp.Add -> {
                    val text = op.text.trim().take(LENGTH)
                    if (text.isEmpty() || result.size >= LIMIT || result.any { it.text == text }) skipped++
                    else { result += Memory(newId(), text, now); added++ }
                }
                is MemoryOp.Update -> {
                    val text = op.text.trim().take(LENGTH)
                    val index = indexOf(op.id)
                    if (text.isEmpty() || index < 0) skipped++
                    else { result[index] = result[index].copy(text = text, updated = now); updated++ }
                }
                is MemoryOp.Delete -> {
                    val index = indexOf(op.id)
                    if (index < 0) skipped++ else { result.removeAt(index); deleted++ }
                }
            }
        }
        return MemoryUpdate(result, listOfNotNull("新增 $added 条".takeIf { added > 0 }, "修改 $updated 条".takeIf { updated > 0 },
            "删除 $deleted 条".takeIf { deleted > 0 }, "忽略 $skipped 条（无效、重复或已满 $LIMIT 条）".takeIf { skipped > 0 })
            .joinToString("，").ifEmpty { "无变化" })
    }

    fun json(memory: List<Memory>) = JSONArray(memory.map { it.json() })
}

/** Memory every install starts with. Each one is added once per id, so deleting it is final. */
object DefaultMemory {
    // A new id, not a new text under "dao-browser": phones that were seeded with the Dao default would never get this one.
    val ALL = listOf(Memory("chrome-browser", "用户说「浏览器」时，一般指 Chrome 浏览器", 0))

    /** Adds the defaults not in [seeded] yet and returns the ids now seeded; a full list gets them on a later load. */
    fun seed(memory: List<Memory>, seeded: Set<String>, now: Long = System.currentTimeMillis()): Pair<List<Memory>, Set<String>> {
        var list = memory
        var done = seeded
        ALL.filterNot { it.id in seeded }.forEach { default ->
            if (list.any { it.id == default.id || it.text == default.text }) done = done + default.id
            else if (list.size < MemoryOps.LIMIT) { list = list + default.copy(updated = now); done = done + default.id }
        }
        return list to done
    }
}
