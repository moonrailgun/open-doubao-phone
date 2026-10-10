package com.moonrailgun.opendoubao.assistant

import com.moonrailgun.opendoubao.assistant.voice.*
import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {
    @Test fun onlyExactControlCommandsBypassTaskInference() {
        assertEquals(VoiceCommand.Stop, parseVoiceCommand("停止任务。"))
        assertEquals(VoiceCommand.Stop, parseVoiceCommand("停止。"))
        assertEquals(VoiceCommand.Resume, parseVoiceCommand("继续"))
        assertEquals(VoiceCommand.Resume, parseVoiceCommand(" 继续任务！ "))
        assertEquals(VoiceCommand.Task("告诉我如何停止任务"), parseVoiceCommand("告诉我如何停止任务"))
        assertEquals(VoiceCommand.Task("打开日历"), parseVoiceCommand("豆馅你好，打开日历。"))
        assertEquals(VoiceCommand.Empty, parseVoiceCommand("你好豆馅。"))
    }

    @Test fun comradeGreetingCanWakeAndPrefixCommands() {
        assertEquals(VoiceCommand.Empty, parseVoiceCommand("同志你好。"))
        assertEquals(VoiceCommand.Task("打开日历"), parseVoiceCommand("同志你好，打开日历。"))
        assertEquals(VoiceCommand.Stop, parseVoiceCommand("同志你好，停止任务。"))
    }

    @Test fun lockedOrDarkScreensNeverAllowTaskExecution() {
        assertTrue(taskScreenAvailable(interactive = true, locked = false))
        assertFalse(taskScreenAvailable(interactive = false, locked = false))
        assertFalse(taskScreenAvailable(interactive = true, locked = true))
    }
}
