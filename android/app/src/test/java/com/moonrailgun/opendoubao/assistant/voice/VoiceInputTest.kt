package com.moonrailgun.opendoubao.assistant.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputTest {
    // The overlay cannot show a permission dialog, so the message must send the user into the app.
    @Test fun missingMicrophonePermissionPointsToTheApp() {
        assertEquals("请先在应用中授权麦克风", voiceInputError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }
}
