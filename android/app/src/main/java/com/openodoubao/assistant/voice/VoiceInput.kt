package com.openodoubao.assistant.voice

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.speech.*
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberVoiceInput(onResult: (String) -> Unit, onMessage: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val message by rememberUpdatedState(onMessage)
    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context,
        ComponentName(context, LocalRecognitionService::class.java)) }
    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { message("正在听，请说话") }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) { message(when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音正在使用，请稍后重试"
                SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请授权麦克风"
                else -> "本地语音不可用，请检查麦克风或在设置中重试语音引擎"
            }) }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(result)
                message("")
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { recognizer.cancel(); recognizer.destroy() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            message("正在加载本地语音")
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        } else message("语音输入需要麦克风权限")
    }
    return { permission.launch(Manifest.permission.RECORD_AUDIO) }
}
