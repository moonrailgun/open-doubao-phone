package com.moonrailgun.opendoubao.assistant.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VoicePromptTest {
    @Test fun bundledPromptsAreMonoPcm16WavFiles() {
        // VoiceRuntime picks wake-1..3 at random and plays retry after an empty transcript.
        for (name in listOf("wake-1", "wake-2", "wake-3", "retry")) {
            val (rate, pcm) = wavPcm(File("src/main/assets/voice/prompts/$name.wav").readBytes())
            assertEquals(name, 24_000, rate)
            assertTrue(name, pcm.size in 4_800..480_000 && pcm.size % 2 == 0)
        }
        val wav = File("src/main/assets/voice/prompts/wake-1.wav").readBytes()
        assertThrows(IllegalArgumentException::class.java) { wavPcm(wav.copyOf(wav.size - 2)) }
        assertThrows(IllegalArgumentException::class.java) { wavPcm(wav.copyOf().also { it[22] = 2 }) } // stereo
        assertThrows(IllegalArgumentException::class.java) { wavPcm(ByteArray(44)) }
    }
}
