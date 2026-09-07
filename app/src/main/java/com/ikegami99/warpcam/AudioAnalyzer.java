package com.ikegami99.warpcam;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AudioAnalyzer {
    private static final AudioAnalyzer INSTANCE = new AudioAnalyzer();
    private static final int SAMPLE_RATE = 44100;
    private static final int FFT_SIZE = 2048;

    private final AtomicReference<AudioSnapshot> snapshot = new AtomicReference<>(AudioSnapshot.silent());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private Thread worker;
    private float smoothedLoudness = 0f;

    public static AudioAnalyzer get() { return INSTANCE; }
    private AudioAnalyzer() {}
    public boolean isRunning() { return running.get(); }

    public AudioSnapshot getSnapshot() {
        AudioSnapshot value = snapshot.get();
        if (System.currentTimeMillis() - value.timestampMs > 1200) return AudioSnapshot.silent();
        return value;
    }

    public synchronized void start(MediaProjection projection) {
        stop();
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuffer, FFT_SIZE * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build();

        audioRecord.startRecording();
        running.set(true);
        worker = new Thread(this::loop, "WarpAudioAnalyzer");
        worker.start();
    }

    public synchronized void stop() {
        running.set(false);
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        snapshot.set(AudioSnapshot.silent());
    }

    private void loop() {
        short[] pcm = new short[FFT_SIZE];
        while (running.get() && audioRecord != null) {
            int read;
            try {
                read = audioRecord.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
            } catch (Exception e) {
                running.set(false);
                break;
            }
            if (read > 0) snapshot.set(analyze(pcm, read));
        }
    }

    private AudioSnapshot analyze(short[] pcm, int count) {
        double sumSq = 0.0;
        int zeroCrossings = 0;
        for (int i = 0; i < count; i++) {
            double v = pcm[i] / 32768.0;
            sumSq += v * v;
            if (i > 0 && ((pcm[i] >= 0) != (pcm[i - 1] >= 0))) zeroCrossings++;
        }
        float rms = (float) Math.sqrt(sumSq / Math.max(1, count));
        float loudness = normalizeRms(rms);

        double[] real = new double[FFT_SIZE];
        double[] imag = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) {
            double sample = i < count ? pcm[i] / 32768.0 : 0.0;
            double window = 0.5 - 0.5 * Math.cos((2.0 * Math.PI * i) / (FFT_SIZE - 1));
            real[i] = sample * window;
        }
        fft(real, imag);

        double bassEnergy = bandEnergy(real, imag, 20, 250);
        double midEnergy = bandEnergy(real, imag, 250, 2000);
        double trebleEnergy = bandEnergy(real, imag, 2000, 12000);
        double total = bassEnergy + midEnergy + trebleEnergy + 1e-12;

        float bass = compressBand(bassEnergy / total, 0.50);
        float mid = compressBand(midEnergy / total, 0.38);
        float treble = compressBand(trebleEnergy / total, 0.24);
        float transientAmount = Math.max(0f, loudness - smoothedLoudness) * 3.2f;
        smoothedLoudness = smoothedLoudness * 0.82f + loudness * 0.18f;
        float zcrBoost = Math.min(1f, zeroCrossings / (float) Math.max(1, count / 3));
        float intensity = Math.min(1f, loudness * 0.55f + bass * 0.30f + treble * 0.15f + transientAmount * 0.25f + zcrBoost * 0.08f);

        return new AudioSnapshot(loudness, bass, mid, treble, transientAmount, intensity, System.currentTimeMillis());
    }

    private static float normalizeRms(float rms) {
        if (rms <= 0.0004f) return 0f;
        double db = 20.0 * Math.log10(rms);
        return clamp01((float) ((db + 52.0) / 42.0));
    }

    private static float compressBand(double ratio, double expectedHigh) {
        return clamp01((float) (ratio / expectedHigh));
    }

    private static double bandEnergy(double[] real, double[] imag, int lowHz, int highHz) {
        int low = Math.max(1, (int) Math.floor(lowHz * FFT_SIZE / (double) SAMPLE_RATE));
        int high = Math.min(FFT_SIZE / 2 - 1, (int) Math.ceil(highHz * FFT_SIZE / (double) SAMPLE_RATE));
        double sum = 0.0;
        for (int i = low; i <= high; i++) sum += real[i] * real[i] + imag[i] * imag[i];
        return sum / Math.max(1, high - low + 1);
    }

    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wLenR = Math.cos(angle);
            double wLenI = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wr = 1.0, wi = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j;
                    int v = i + j + len / 2;
                    double vr = real[v] * wr - imag[v] * wi;
                    double vi = real[v] * wi + imag[v] * wr;
                    real[v] = real[u] - vr;
                    imag[v] = imag[u] - vi;
                    real[u] += vr;
                    imag[u] += vi;
                    double nextWr = wr * wLenR - wi * wLenI;
                    wi = wr * wLenI + wi * wLenR;
                    wr = nextWr;
                }
            }
        }
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
