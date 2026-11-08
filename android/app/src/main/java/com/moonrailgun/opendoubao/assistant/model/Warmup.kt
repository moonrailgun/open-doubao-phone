package com.moonrailgun.opendoubao.assistant.model

import android.content.Context
import com.moonrailgun.opendoubao.assistant.AppLifecycleService
import com.moonrailgun.opendoubao.assistant.data.Vault

/** Opens connections to the configured model servers as the user starts a command (see [HttpTransport.warm]). */
fun warmModels(context: Context) {
    val app = context.applicationContext
    Thread {
        val endpoints = runCatching { Vault(app).read().config.let { listOf(it.jevEndpoint, it.chatEndpoint) } }.getOrNull() ?: return@Thread
        app.mainExecutor.execute { if (!AppLifecycleService.isExited(app)) HttpTransport.warm(endpoints) }
    }.start()
}
