package com.moonrailgun.opendoubao.assistant.phone

import kotlinx.coroutines.*

internal suspend fun awaitCompanionPreview(speech: Deferred<Boolean>?) = coroutineScope {
    // A failed or disabled voice must still leave enough time to read the comment.
    val reading = async { delay(5_000) }
    if (speech?.await() == true) reading.cancel() else reading.await()
}
