package com.openodoubao.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class EngineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == TextToSpeech.Engine.ACTION_CHECK_TTS_DATA) {
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, Intent().apply {
                putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, arrayListOf("zho-CHN"))
                putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
            })
            finish()
            return
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(this@EngineActivity).apply { text = "豆馅本地语音引擎\n中文语音随应用安装，无需联网。若上次加载意外中断，可重试初始化。" })
            addView(Button(this@EngineActivity).apply {
                text = "重试初始化"
                setOnClickListener {
                    getSharedPreferences("engine", MODE_PRIVATE).edit().remove("initializing").apply()
                    text = "已重置，请返回助手测试播报"
                }
            })
        })
    }
}
