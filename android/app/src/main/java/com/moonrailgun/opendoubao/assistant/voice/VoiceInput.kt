package com.moonrailgun.opendoubao.assistant.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.*
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

fun voiceAutoSend(context: Context) =
    context.getSharedPreferences("voice", Context.MODE_PRIVATE).getBoolean("auto_send", false)

internal fun voiceInputError(error: Int) = when (error) {
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音正在使用，请稍后重试"
    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请先在应用中授权麦克风"
    else -> "本地语音不可用，请检查麦克风或在设置中重试语音引擎"
}

/** Dictation through the bundled offline recognizer; create and use it on the main thread. */
class LocalVoiceInput(context: Context, onResult: (String) -> Unit, private val onMessage: (String) -> Unit) {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context,
        ComponentName(context, LocalRecognitionService::class.java)).apply {
        setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { onMessage("正在听，请说话") }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) { onMessage(voiceInputError(error)) }
            override fun onResults(results: Bundle?) {
                // Clear first so an auto-sent task can report its own error.
                onMessage("")
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onResult)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }
    fun start() {
        onMessage("正在加载本地语音")
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }
    fun destroy() { recognizer.cancel(); recognizer.destroy() }
}

@Composable
fun rememberVoiceInput(onResult: (String) -> Unit, onMessage: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val message by rememberUpdatedState(onMessage)
    val input = remember { LocalVoiceInput(context, { result(it) }, { message(it) }) }
    DisposableEffect(input) { onDispose { input.destroy() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) input.start() else message("语音输入需要麦克风权限")
    }
    return { permission.launch(Manifest.permission.RECORD_AUDIO) }
}
