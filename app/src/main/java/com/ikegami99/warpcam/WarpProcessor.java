package com.ikegami99.warpcam;

import android.graphics.Bitmap;

import java.util.Random;

public final class WarpProcessor {
    private WarpProcessor() {}

    public static Bitmap process(Bitmap input, AudioSnapshot audio, float sensitivity) {
        int w = input.getWidth();
        int h = input.getHeight();
        int[] src = new int[w * h];
        int[] out = new int[w * h];
        input.getPixels(src, 0, w, 0, 0, w, h);

        float s = Math.max(0f, Math.min(2.5f, sensitivity));
        float intensity = audio.intensity * s;
        float bass = audio.bass * s;
        float treble = audio.treble * s;
        float transientAmount = audio.transientAmount * s;

        int maxWave = Math.round(Math.min(w, h) * (0.006f + 0.055f * intensity));
        int rgbShift = Math.round(Math.min(w, h) * Math.min(0.035f,
                0.002f + treble * 0.018f + transientAmount * 0.012f));
        float twist = Math.min(0.22f, bass * 0.12f + intensity * 0.06f);
        float brightness = 1.0f + Math.min(0.42f, intensity * 0.28f + transientAmount * 0.12f);
        float saturation = 1.0f + Math.min(1.15f, intensity * 0.62f + treble * 0.35f);
        float phase = (audio.timestampMs % 10000L) / 10000f * (float) Math.PI * 2f;

        int[] rowShift = new int[h];
        int[] colShift = new int[w];
        for (int y = 0; y < h; y++) {
            double wave = Math.sin(phase + y * (0.018 + audio.mid * 0.028));
            rowShift[y] = (int) Math.round(wave * maxWave * (0.25 + bass * 0.75));
        }
        for (int x = 0; x < w; x++) {
            double wave = Math.sin(phase * 1.7 + x * (0.013 + treble * 0.024));
            colShift[x] = (int) Math.round(wave * maxWave * 0.35 * (0.2 + treble));
        }

        Random random = new Random(audio.timestampMs / 80L);
        int glitchBands = Math.min(18, Math.round((transientAmount * 7f + treble * 4f) * s));
        for (int i = 0; i < glitchBands; i++) {
            int y0 = random.nextInt(Math.max(1, h));
            int bandH = 2 + random.nextInt(Math.max(3, h / 45));
            int offset = (int) ((random.nextFloat() * 2f - 1f) * Math.min(w * 0.12f, maxWave * 3.5f));
            for (int y = y0; y < Math.min(h, y0 + bandH); y++) rowShift[y] += offset;
        }

        float cx = (w - 1) * 0.5f;
        float cy = (h - 1) * 0.5f;
        float invRx = 1f / Math.max(1f, cx);
        float invRy = 1f / Math.max(1f, cy);

        for (int y = 0; y < h; y++) {
            float ny = (y - cy) * invRy;
            for (int x = 0; x < w; x++) {
                float nx = (x - cx) * invRx;
                float r2 = nx * nx + ny * ny;
                float edge = Math.max(0f, 1f - Math.min(1f, r2));

                int sx = x + rowShift[y] + Math.round((y - cy) * twist * edge);
                int sy = y + colShift[x] - Math.round((x - cx) * twist * edge);
                sx = clamp(sx, 0, w - 1);
                sy = clamp(sy, 0, h - 1);

                int base = src[sy * w + sx];
                int rSource = src[sy * w + clamp(sx + rgbShift, 0, w - 1)];
                int bSource = src[sy * w + clamp(sx - rgbShift, 0, w - 1)];

                int r = (rSource >> 16) & 0xff;
                int g = (base >> 8) & 0xff;
                int b = bSource & 0xff;
                float gray = r * 0.299f + g * 0.587f + b * 0.114f;
                r = clamp(Math.round((gray + (r - gray) * saturation) * brightness), 0, 255);
                g = clamp(Math.round((gray + (g - gray) * saturation) * brightness), 0, 255);
                b = clamp(Math.round((gray + (b - gray) * saturation) * brightness), 0, 255);

                int colorPush = Math.round(24f * Math.min(1f, treble + bass) * s);
                r = clamp(r + Math.round(colorPush * bass * 0.65f), 0, 255);
                b = clamp(b + Math.round(colorPush * treble * 0.85f), 0, 255);
                out[y * w + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        return result;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
