package com.openodoubao.assistant.voice

import android.content.Intent
import android.speech.RecognitionService

class LocalRecognitionService : RecognitionService() {
    private var owner: Callback? = null
    private var request: Long? = null
    override fun onStartListening(intent: Intent, listener: Callback) {
        owner = listener
        request = VoiceRuntime.recognize(applicationContext, listener)
    }
    override fun onStopListening(listener: Callback) { if (owner === listener) request?.let(VoiceRuntime::stopListening) }
    override fun onCancel(listener: Callback) {
        if (owner === listener) { request?.let(VoiceRuntime::cancel); owner = null; request = null }
    }
    override fun onDestroy() { request?.let(VoiceRuntime::cancel); super.onDestroy() }
}
