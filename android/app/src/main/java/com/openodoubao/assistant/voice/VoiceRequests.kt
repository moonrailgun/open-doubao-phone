package com.openodoubao.assistant.voice

import com.openodoubao.assistant.core.Markdown

/** A cancelled request keeps ownership until its recorder has actually closed. */
class VoiceRequests {
    private var sequence = 0L
    private var active: Long? = null
    private var cancelled = false

    @Synchronized fun begin(): Long? {
        if (active != null) return null
        cancelled = false
        return (++sequence).also { active = it }
    }

    @Synchronized fun accepts(id: Long) = active == id && !cancelled
    @Synchronized fun cancel(id: Long? = active) { if (active == id) cancelled = true }
    @Synchronized fun finish(id: Long): Boolean {
        if (active != id) return false
        active = null
        return !cancelled
    }
}

fun speechText(markdown: String): String {
    val text = Markdown.parse(markdown).text
    if (text.codePointCount(0, text.length) <= 300) return text
    return text.substring(0, text.offsetByCodePoints(0, 300)) + "。更多内容请查看全文。"
}
