package com.openodoubao.assistant.voice

val wakePhrases = listOf("豆馅你好", "你好豆馅", "豆馅同志", "同志你好")

sealed interface VoiceCommand {
    data object Stop : VoiceCommand
    data object Resume : VoiceCommand
    data object Empty : VoiceCommand
    data class Task(val goal: String) : VoiceCommand
}

fun parseVoiceCommand(input: String): VoiceCommand {
    fun clean(text: String) = text.trim { it.isWhitespace() || it in "，。！？,.!?：:" }
    var text = clean(input)
    wakePhrases.firstOrNull { text.startsWith(it) }?.let { text = clean(text.removePrefix(it)) }
    return when (text) {
        "" -> VoiceCommand.Empty
        "停止", "停止任务" -> VoiceCommand.Stop
        "继续", "继续任务" -> VoiceCommand.Resume
        else -> VoiceCommand.Task(text)
    }
}

fun taskScreenAvailable(interactive: Boolean, locked: Boolean) = interactive && !locked
