package com.moonrailgun.opendoubao.assistant.voice

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class VoicePromptTest {
    private val prompts get() = JSONObject(File("src/main/assets/voice/prompts/prompts.json").readText())

    @Test fun fixedRepliesResolveToBundledAudioButResultsAndAuditionsStillSynthesize() {
        for (text in listOf("已停止任务。", "没有听清，请重新唤醒后说出任务。",
            "请先点亮并解锁手机，然后重新下达指令。", "当前没有可继续的暂停任务",
            "请先在设置中启用豆馅无障碍服务", "请求或任务超时")) {
            val asset = voicePrompt(text, prompts)
            assertNotNull(text, asset)
            assertTrue(text, File("src/main/assets/$asset").isFile)
        }
        assertNull(voicePrompt("你好，我是豆馅。很高兴为你服务。", prompts))
        assertNull(voicePrompt("已找到今天下午三点的会议。", prompts))
        assertNull(voicePrompt("无法打开目标应用 com.example.app，请手动进入后点击继续", prompts))
    }

    @Test fun bundledPromptsAreMonoPcm16WavFiles() {
        for (name in listOf("wake-1", "wake-2", "wake-3", "retry")) assertTrue(name, prompts.has(name))
        val texts = mutableSetOf<String>()
        for (name in prompts.keys()) {
            assertTrue("duplicate text: $name", texts.add(prompts.getString(name)))
            assertEquals("voice/prompts/$name.wav", voicePrompt(prompts.getString(name), prompts))
            val (rate, pcm) = wavPcm(File("src/main/assets/voice/prompts/$name.wav").readBytes())
            assertEquals(name, 24_000, rate)
            assertTrue(name, pcm.size in 4_800..1_440_000 && pcm.size % 2 == 0)
            assertTrue("silent prompt: $name", pcm.any { it != 0.toByte() })
        }
        val wav = File("src/main/assets/voice/prompts/wake-1.wav").readBytes()
        assertThrows(IllegalArgumentException::class.java) { wavPcm(wav.copyOf(wav.size - 2)) }
        assertThrows(IllegalArgumentException::class.java) { wavPcm(wav.copyOf().also { it[22] = 2 }) } // stereo
        assertThrows(IllegalArgumentException::class.java) { wavPcm(ByteArray(44)) }
    }
}
