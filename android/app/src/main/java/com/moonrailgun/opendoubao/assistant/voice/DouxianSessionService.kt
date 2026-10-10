package com.moonrailgun.opendoubao.assistant.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class DouxianSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            // Our ongoing notification and accessibility overlay own the task controls.
            setUiEnabled(false)
            VoiceRuntime.listenForTask(this@DouxianSessionService)
            hide()
        }
    }
}
