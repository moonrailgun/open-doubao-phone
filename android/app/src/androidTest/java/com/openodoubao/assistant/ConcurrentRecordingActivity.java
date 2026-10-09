package com.openodoubao.assistant;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/** Separate test APK UID; uses only platform classes and never reads or saves captured audio. */
public class ConcurrentRecordingActivity extends Activity {
    private AudioRecord record;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("Microphone competition test (10 seconds)");
        setContentView(text);
    }

    @SuppressLint("MissingPermission") // The test grants RECORD_AUDIO before launching this activity.
    @Override public void onResume() {
        super.onResume();
        record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setPrivacySensitive(true)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(Math.max(4096, AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))).build();
        record.startRecording();
        handler.postDelayed(this::finishAndRemoveTask, 10_000);
    }

    @Override public void onStop() {
        if (record != null) { record.release(); record = null; }
        handler.removeCallbacksAndMessages(null);
        super.onStop();
        finishAndRemoveTask();
    }
}
