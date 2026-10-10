package com.moonrailgun.opendoubao.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.moonrailgun.opendoubao.assistant.voice.DouxianInteractionService
import com.moonrailgun.opendoubao.assistant.voice.VoiceRuntime
import org.junit.Assert.*
import org.junit.Test

/** Run on the development phone after selecting this package as its default assistant. */
class WakeLifecycleTest {
    @Test fun wakeListeningSurvivesPlaybackMuteAndBackground() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val wasMuted = audio.isMicrophoneMute
        assertTrue("Select the default assistant first", VoiceInteractionService.isActiveService(context,
            ComponentName(context, DouxianInteractionService::class.java)))
        instrument.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrument.runOnMainSync {
            activity.setTurnScreenOn(true)
            context.getSystemService(KeyguardManager::class.java).requestDismissKeyguard(activity, null)
        }
        fun awaitState(expected: String) {
            val end = SystemClock.elapsedRealtime() + 30_000
            while (VoiceRuntime.state.value != expected && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
            assertEquals(VoiceRuntime.error.value, expected, VoiceRuntime.state.value)
        }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            // Instrumentation force-stops the target; restore the real system binding it tears down.
            shell("settings put secure voice_interaction_service ''")
            shell("settings put secure voice_interaction_service com.moonrailgun.opendoubao.assistant/.voice.DouxianInteractionService")
            instrument.runOnMainSync { VoiceRuntime.setEnabled(context, true) }
            awaitState("等待唤醒词")
            instrument.runOnMainSync { assertTrue(VoiceRuntime.speak(context, "语音测试。")) }
            awaitState("等待唤醒词")
            instrument.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MODIFY_AUDIO_SETTINGS)
            audio.isMicrophoneMute = true
            assertTrue("The platform must actually mute the microphone", audio.isMicrophoneMute)
            awaitState("麦克风暂停，等待恢复")
            audio.isMicrophoneMute = false
            awaitState("等待唤醒词")
            instrument.uiAutomation.grantRuntimePermission(instrument.context.packageName, Manifest.permission.RECORD_AUDIO)
            context.startActivity(Intent().setClassName(instrument.context.packageName,
                ConcurrentRecordingActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val device = UiDevice.getInstance(instrument)
            assertTrue(device.wait(Until.hasObject(By.text("Microphone competition test (10 seconds)")), 5_000))
            awaitState("麦克风暂停，等待恢复")
            assertEquals("麦克风被系统暂停", VoiceRuntime.error.value)
            assertTrue(device.wait(Until.gone(By.pkg(instrument.context.packageName)), 15_000))
            awaitState("等待唤醒词")
            instrument.runOnMainSync { activity.finishAndRemoveTask() }
            SystemClock.sleep(1_000)
            assertEquals("等待唤醒词", VoiceRuntime.state.value)
            repeat(3) {
                instrument.runOnMainSync { VoiceRuntime.setEnabled(context, false) }
                awaitState("空闲")
                instrument.runOnMainSync { VoiceRuntime.setEnabled(context, true) }
                awaitState("等待唤醒词")
            }
        } finally {
            audio.isMicrophoneMute = wasMuted
            instrument.uiAutomation.dropShellPermissionIdentity()
            instrument.runOnMainSync {
                VoiceRuntime.setEnabled(context, false)
                VoiceRuntime.cancel()
                activity.finish()
            }
        }
    }

    @Test fun enableForRebootCheck() {
        if (InstrumentationRegistry.getArguments().getString("enableWake") != "true") return
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync { VoiceRuntime.setEnabled(instrument.targetContext, true) }
        // Flush apply() before the instrumentation process is terminated.
        assertTrue(instrument.targetContext.getSharedPreferences("voice", 0).edit().commit())
    }
}
