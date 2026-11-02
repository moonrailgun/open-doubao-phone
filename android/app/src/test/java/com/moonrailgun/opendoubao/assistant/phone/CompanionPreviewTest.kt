package com.moonrailgun.opendoubao.assistant.phone

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionPreviewTest {
    @Test fun asynchronousSpeechFailureKeepsTextForFiveSeconds() = runTest {
        val speech = CompletableDeferred<Boolean>()
        val preview = launch { awaitCompanionPreview(speech) }
        runCurrent()
        advanceTimeBy(100)
        speech.complete(false)
        runCurrent()
        assertTrue(preview.isActive)
        advanceTimeBy(4899); runCurrent()
        assertTrue(preview.isActive)
        advanceTimeBy(1); runCurrent()
        assertTrue(preview.isCompleted)
    }

    @Test fun textOnlyCommentStaysForFiveSeconds() = runTest {
        val preview = launch { awaitCompanionPreview(null) }
        runCurrent()
        advanceTimeBy(4999); runCurrent()
        assertTrue(preview.isActive)
        advanceTimeBy(1); runCurrent()
        assertTrue(preview.isCompleted)
    }

    @Test fun successfulSpeechHidesTextWhenPlaybackEnds() = runTest {
        val speech = CompletableDeferred<Boolean>()
        val preview = launch { awaitCompanionPreview(speech) }
        runCurrent()
        advanceTimeBy(1000)
        speech.complete(true)
        runCurrent()
        assertTrue(preview.isCompleted)
        assertEquals(1000L, currentTime)
    }

    @Test fun longSpeechKeepsTextAndStoppingCancelsThePreview() = runTest {
        val speech = CompletableDeferred<Boolean>()
        val preview = launch { awaitCompanionPreview(speech) }
        advanceTimeBy(6000); runCurrent()
        assertTrue(preview.isActive)
        preview.cancelAndJoin()
        assertTrue(preview.isCancelled)
    }
}
