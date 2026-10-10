package com.moonrailgun.opendoubao.assistant.voice

val wakePhrases = listOf("豆馅你好", "你好豆馅", "豆馅同志", "同志你好")

sealed interface VoiceCommand {
    data object Stop : VoiceCommand
    data object Resume : VoiceCommand
    data object Empty : VoiceCommand
    data class Task(val goal: String) : VoiceCommand
}

private fun clean(text: String) = text.trim { it.isWhitespace() || it in "，。！？,.!?：:" }

/** The command spoken after a detected wake word, or "" when only the wake word was heard. */
fun wakeCommand(transcript: String): String {
    val text = clean(transcript)
    val exact = wakePhrases.mapNotNull { phrase -> text.indexOf(phrase).takeIf { it >= 0 }?.plus(phrase.length) }.minOrNull()
    // ponytail: ASR spells the wake word with homophones (豆先你好, 窦谢同志), so drop its four syllables once
    // KWS has fired; a syllable the ASR drops shifts the cut into the command. Cut on KWS timestamps if that shows up.
    var syllables = 0
    val end = exact ?: (text.indexOfFirst { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN && ++syllables == 4 } + 1)
        .takeIf { it > 0 } ?: text.length
    val command = clean(text.substring(end))
    return if (command.count { it.isLetterOrDigit() } < 2) "" else command
}

fun parseVoiceCommand(input: String): VoiceCommand {
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
