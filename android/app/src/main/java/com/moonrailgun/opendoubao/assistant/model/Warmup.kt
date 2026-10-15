package com.moonrailgun.opendoubao.assistant.model

import android.content.Context
import com.moonrailgun.opendoubao.assistant.data.Vault

/** Opens connections to the configured model servers as the user starts a command (see [HttpTransport.warm]). */
fun warmModels(context: Context) {
    val app = context.applicationContext
    Thread { runCatching { Vault(app).read().config.let { HttpTransport.warm(listOf(it.jevEndpoint, it.chatEndpoint)) } } }.start()
}
