package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.model.CompanionConfig
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionConfigTest {
    private val general = ModelConfig(chatEndpoint = "https://general.test/chat", chatModel = "vision", chatKey = "general",
        jevKey = "jev", jevVision = true)

    @Test fun speechIsOptInAndPersistsIndependently() {
        assertFalse(CompanionConfig.fromJson(null).speechEnabled)
        assertFalse(CompanionConfig.fromJson(JSONObject("{\"intervalSeconds\":45}")).speechEnabled)
        for (enabled in listOf(true, false)) {
            val config = CompanionConfig(speechEnabled = enabled)
            assertEquals(config, CompanionConfig.fromJson(JSONObject(config.json().toString())))
        }
    }

    @Test fun oldSettingsDefaultToGeneralModelAndDedicatedSettingsRoundTrip() {
        val defaults = CompanionConfig.fromJson(null)
        assertEquals(10, defaults.intervalSeconds)
        assertEquals(10, CompanionConfig.fromJson(JSONObject()).intervalSeconds)
        assertEquals(20, CompanionConfig.fromJson(JSONObject().put("intervalSeconds", 20)).intervalSeconds)
        for (seconds in 1..4) CompanionConfig(intervalSeconds = seconds).modelConfig(general)
        assertEquals("vision", defaults.modelConfig(general).chatModel)
        assertTrue(defaults.modelConfig(general).hasJev)
        assertTrue(defaults.modelConfig(general).jevVision)
        val dedicated = CompanionConfig(45, true, "https://dedicated.test/chat", "small-vision", "dedicated")
        assertEquals(dedicated, CompanionConfig.fromJson(JSONObject(dedicated.json().toString())))
        val chosen = dedicated.modelConfig(ModelConfig())
        assertEquals("https://dedicated.test/chat", chosen.chatEndpoint)
        assertEquals("small-vision", chosen.chatModel)
        assertEquals("dedicated", chosen.chatKey)
        val withJev = dedicated.modelConfig(general)
        assertEquals(general.jevEndpoint, withJev.jevEndpoint)
        assertEquals(general.jevModel, withJev.jevModel)
        assertEquals(general.jevKey, withJev.jevKey)
        assertTrue(withJev.jevVision)
        assertEquals("small-vision", withJev.chatModel)
        assertFalse(dedicated.modelConfig(general.copy(jevVision = false)).hasJev)
        assertThrows(IllegalArgumentException::class.java) {
            dedicated.modelConfig(general.copy(jevKey = ""))
        }
    }

    @Test fun rejectsInvalidIntervalsAndIncompleteOrInsecureDedicatedModel() {
        for (seconds in listOf(0, -1, 301)) {
            assertThrows(IllegalArgumentException::class.java) { CompanionConfig(intervalSeconds = seconds).modelConfig(general) }
        }
        for (config in listOf(CompanionConfig(useDedicatedModel = true),
            CompanionConfig(20, true, "http://unsafe.test/chat", "vision", "key"),
            CompanionConfig(20, true, "https://safe.test/chat", "vision", "bad key"))) {
            assertThrows(IllegalArgumentException::class.java) { config.modelConfig(general) }
        }
    }
}
