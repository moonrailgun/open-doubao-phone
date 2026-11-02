package com.moonrailgun.opendoubao.assistant

import android.content.Intent
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import com.moonrailgun.opendoubao.assistant.voice.FileSpeech
import org.junit.Assert.*
import org.junit.Test

class CompanionSpeechInterruptionTest {
    @Test fun focusLossReportsInterruptionInsteadOfSuccessfulCompletion() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrument.runOnMainSync {
                for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
                    val results = mutableListOf<String?>()
                    val speech = FileSpeech(context, unlockPrompt = true) { results.add(it) }
                    try {
                        speech.play("voice/prompts/step-limit.wav")
                        assertTrue("Playback must start before interruption: $results", results.isEmpty())
                        // Invoke the registered platform callback without taking focus from another app.
                        speech.onAudioFocusChange(loss)
                        assertEquals(listOf("音频焦点丢失，播报中断"), results)
                        speech.stop()
                        assertEquals("Completion is delivered only once", 1, results.size)
                    } finally { speech.stop() }
                }
            }
        } finally { instrument.runOnMainSync { activity.finish() } }
    }
}
