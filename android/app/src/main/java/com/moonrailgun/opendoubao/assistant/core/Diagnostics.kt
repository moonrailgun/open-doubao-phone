package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The latest voice and placement events of this process, oldest first, for the debug page. */
object Diagnostics {
    const val LIMIT = 200
    private val entries = ArrayDeque<String>()
    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val events = mutable.asStateFlow()

    fun log(event: String) = synchronized(entries) {
        entries.addLast(logLine(event))
        while (entries.size > LIMIT) entries.removeFirst()
        mutable.value = entries.toList()
    }
}
