package com.moonrailgun.opendoubao.assistant

import android.app.Activity
import android.os.Bundle
import com.moonrailgun.opendoubao.assistant.voice.VoiceRuntime

// The image's framework overlay makes a double press of the power key open this; it listens for a task as the assist
// gesture does, with no window of its own.
class PowerPressActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceRuntime.listenForTask(this)
        finish()
    }
}
