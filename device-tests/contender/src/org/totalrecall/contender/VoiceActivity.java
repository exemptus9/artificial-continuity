package org.totalrecall.contender;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

/** Disposable emulator fixture. Uses a separate UID and discards every microphone frame. */
public final class VoiceActivity extends Activity {
    private TextView state;
    private volatile boolean running;
    private Thread worker;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = new TextView(this);
        state.setText("Opening competing microphone");
        setContentView(state);
    }

    @Override public void onResume() {
        super.onResume();
        running = true;
        worker = new Thread(this::capture, "competing-voice-mode");
        worker.start();
    }

    private void capture() {
        AudioRecord recorder = null;
        try {
            AudioFormat format = new AudioFormat.Builder().setSampleRate(16000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION).setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(16000, AudioRecord.getMinBufferSize(16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)));
            if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(true);
            recorder = builder.build();
            recorder.startRecording();
            short[] frame = new short[1600];
            boolean shown = false;
            while (running) {
                int count = recorder.read(frame, 0, frame.length);
                if (count <= 0) throw new IllegalStateException("AudioRecord read " + count);
                if (!shown) {
                    shown = true;
                    runOnUiThread(() -> state.setText("Competing microphone active"));
                }
            }
        } catch (RuntimeException e) {
            runOnUiThread(() -> state.setText("Competing microphone failed: " + e));
        } finally {
            if (recorder != null) recorder.release();
        }
    }

    @Override public void onPause() {
        running = false;
        try {
            if (worker != null) worker.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        super.onPause();
    }
}
