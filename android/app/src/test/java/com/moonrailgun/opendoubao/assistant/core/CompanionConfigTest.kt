package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.model.CompanionConfig
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionConfigTest {
    private val general = ModelConfig(chatEndpoint = "https://general.test/chat", chatModel = "vision", chatKey = "general",
        jevKey = "jev", jevVision = true)

    @Test fun oldSettingsDefaultToGeneralModelAndDedicatedSettingsRoundTrip() {
        val defaults = CompanionConfig.fromJson(null)
        assertEquals(20, defaults.intervalSeconds)
        assertEquals("vision", defaults.modelConfig(general).chatModel)
        assertFalse(defaults.modelConfig(general).hasJev)
        assertFalse(defaults.modelConfig(general).jevVision)
        val dedicated = CompanionConfig(45, true, "https://dedicated.test/chat", "small-vision", "dedicated")
        assertEquals(dedicated, CompanionConfig.fromJson(JSONObject(dedicated.json().toString())))
        val chosen = dedicated.modelConfig(ModelConfig())
        assertEquals("https://dedicated.test/chat", chosen.chatEndpoint)
        assertEquals("small-vision", chosen.chatModel)
        assertEquals("dedicated", chosen.chatKey)
    }

    @Test fun rejectsInvalidIntervalsAndIncompleteOrInsecureDedicatedModel() {
        for (seconds in listOf(0, -1, 4, 301)) {
            assertThrows(IllegalArgumentException::class.java) { CompanionConfig(intervalSeconds = seconds).modelConfig(general) }
        }
        for (config in listOf(CompanionConfig(useDedicatedModel = true),
            CompanionConfig(20, true, "http://unsafe.test/chat", "vision", "key"),
            CompanionConfig(20, true, "https://safe.test/chat", "vision", "bad key"))) {
            assertThrows(IllegalArgumentException::class.java) { config.modelConfig(general) }
        }
    }
}
