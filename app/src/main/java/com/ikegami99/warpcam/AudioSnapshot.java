package com.ikegami99.warpcam;

public final class AudioSnapshot {
    public final float loudness;
    public final float bass;
    public final float mid;
    public final float treble;
    public final float transientAmount;
    public final float intensity;
    public final long timestampMs;

    public AudioSnapshot(float loudness, float bass, float mid, float treble, float transientAmount, float intensity, long timestampMs) {
        this.loudness = clamp01(loudness);
        this.bass = clamp01(bass);
        this.mid = clamp01(mid);
        this.treble = clamp01(treble);
        this.transientAmount = clamp01(transientAmount);
        this.intensity = clamp01(intensity);
        this.timestampMs = timestampMs;
    }

    public static AudioSnapshot silent() {
        return new AudioSnapshot(0f, 0f, 0f, 0f, 0f, 0f, System.currentTimeMillis());
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
