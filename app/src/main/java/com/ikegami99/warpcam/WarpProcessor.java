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

        float s = clamp01(sensitivity / 2.5f) * 2.5f;
        float loud = clamp01(audio.loudness * s);
        float intensity = clamp01(audio.intensity * s);
        float bass = clamp01(audio.bass * s);
        float mid = clamp01(audio.mid * s);
        float treble = clamp01(audio.treble * s);
        float hit = clamp01(audio.transientAmount * s * 1.25f);

        float bassDrive = clamp01(bass * 0.82f + intensity * 0.34f);
        float midDrive = clamp01(mid * 0.78f + intensity * 0.24f + hit * 0.18f);
        float highDrive = clamp01(treble * 0.84f + hit * 0.32f);
        float chaos = clamp01(hit * 0.72f + treble * 0.22f + intensity * 0.18f);

        float phase = (audio.timestampMs % 12000L) / 12000f * (float) Math.PI * 2f;
        long seed = audio.timestampMs / 90L;
        Random random = new Random(seed);

        // Geometry stack ----------------------------------------------------
        int minDim = Math.min(w, h);
        int maxWave = Math.round(minDim * (0.004f + 0.050f * intensity));
        float twist = Math.min(0.34f, midDrive * 0.16f + bassDrive * 0.12f);
        float bulge = Math.min(0.32f, bassDrive * 0.27f + hit * 0.08f);
        float ringStrength = Math.min(0.11f, bassDrive * 0.055f + midDrive * 0.045f);
        int rgbShift = Math.round(minDim * Math.min(0.048f,
                0.0015f + highDrive * 0.026f + hit * 0.020f));
        int echoShiftX = Math.round(w * Math.min(0.050f, midDrive * 0.028f + hit * 0.018f));
        int echoShiftY = Math.round(h * Math.min(0.032f, treble * 0.015f + midDrive * 0.010f));
        float echoMix = Math.min(0.34f, midDrive * 0.22f + intensity * 0.10f);

        int[] rowShift = new int[h];
        float[] rowTone = new float[h];
        for (int y = 0; y < h; y++) {
            double a = Math.sin(phase * 1.25 + y * (0.014 + mid * 0.030));
            double b = Math.sin(-phase * 2.10 + y * (0.037 + treble * 0.040));
            rowShift[y] = Math.round((float) (a * maxWave * (0.22 + bassDrive * 0.78)
                    + b * maxWave * 0.21 * highDrive));

            float scan = ((y & 3) == 0) ? 1f : 0f;
            rowTone[y] = 1f - scan * highDrive * 0.10f;
        }

        int[] colShift = new int[w];
        for (int x = 0; x < w; x++) {
            double a = Math.sin(phase * 1.70 + x * (0.010 + treble * 0.028));
            double b = Math.sin(-phase * 0.90 + x * (0.029 + mid * 0.024));
            colShift[x] = Math.round((float) (a * maxWave * 0.30 * (0.15 + highDrive)
                    + b * maxWave * 0.16 * midDrive));
        }

        // Horizontal digital tears react mostly to hits and high frequencies.
        int glitchBands = Math.min(26, Math.round(chaos * 22f));
        for (int i = 0; i < glitchBands; i++) {
            int y0 = random.nextInt(Math.max(1, h));
            int maxBand = Math.max(4, h / 28);
            int bandH = 2 + random.nextInt(maxBand);
            int offset = Math.round((random.nextFloat() * 2f - 1f)
                    * Math.min(w * 0.18f, maxWave * (2.2f + chaos * 4.8f)));
            for (int y = y0; y < Math.min(h, y0 + bandH); y++) {
                rowShift[y] += offset;
                if (hit > 0.58f) rowTone[y] *= 0.86f + random.nextFloat() * 0.28f;
            }
        }

        float cx = (w - 1) * 0.5f;
        float cy = (h - 1) * 0.5f;
        float invRx = 1f / Math.max(1f, cx);
        float invRy = 1f / Math.max(1f, cy);

        // Color stack -------------------------------------------------------
        float brightness = 1.0f + Math.min(0.56f,
                intensity * 0.25f + loud * 0.12f + hit * 0.27f);
        float saturation = 1.0f + Math.min(1.65f,
                intensity * 0.52f + treble * 0.60f + hit * 0.30f);
        float hueAngle = (bass - treble) * 0.95f + (float) Math.sin(phase) * intensity * 0.42f;
        HueMatrix hue = new HueMatrix(hueAngle);
        float posterMix = clamp01((highDrive - 0.34f) * 1.20f + hit * 0.24f);
        int posterLevels = highDrive > 0.78f ? 4 : (highDrive > 0.52f ? 6 : 9);
        float solarizeMix = clamp01((hit - 0.60f) * 1.65f + Math.max(0f, treble - 0.78f) * 0.55f);
        float invertMix = clamp01((hit - 0.82f) * 2.2f);
        float noiseAmount = (treble * 5.5f + chaos * 7.5f) * s;
        float vignetteAmount = 0.06f + intensity * 0.13f;

        int pixelBlock = Math.max(2, Math.round(minDim * (0.010f + chaos * 0.022f)));
        float pixelChance = chaos * 0.28f;
        int mirrorCell = Math.max(pixelBlock * 3, minDim / 9);
        float mirrorGate = clamp01((hit - 0.50f) * 1.8f + Math.max(0f, highDrive - 0.68f));

        for (int y = 0; y < h; y++) {
            float ny = (y - cy) * invRy;
            for (int x = 0; x < w; x++) {
                float nx = (x - cx) * invRx;
                float r2 = nx * nx + ny * ny;
                float inside = 1f - Math.min(1f, r2);

                // Bass bulge/pinch + concentric shockwave.
                float radialScale = 1f - bulge * inside;
                float ring = (float) Math.sin(r2 * (18f + bass * 16f) - phase * 4.0f)
                        * ringStrength * inside;
                radialScale += ring;

                float dx = (x - cx) * radialScale;
                float dy = (y - cy) * radialScale;

                // Midrange twist around the image centre.
                float twistLocal = twist * inside;
                float tx = dx + dy * twistLocal;
                float ty = dy - dx * twistLocal;

                int sx = Math.round(cx + tx) + rowShift[y];
                int sy = Math.round(cy + ty) + colShift[x];

                // Block collapse / datamosh chunks on strong transients.
                if (pixelChance > 0.04f) {
                    int bx = x / pixelBlock;
                    int by = y / pixelBlock;
                    float gate = hash01(bx, by, seed);
                    if (gate < pixelChance) {
                        sx = (sx / pixelBlock) * pixelBlock + pixelBlock / 2;
                        sy = (sy / pixelBlock) * pixelBlock + pixelBlock / 2;
                    }
                }

                // Mirror shards appear only on the nastier peaks.
                if (mirrorGate > 0.08f) {
                    int mx = x / mirrorCell;
                    int my = y / mirrorCell;
                    float gate = hash01(mx + 91, my + 37, seed / 3L);
                    if (gate < mirrorGate * 0.30f) {
                        if (((mx + my) & 1) == 0) sx = w - 1 - sx;
                        else sy = h - 1 - sy;
                    }
                }

                sx = clamp(sx, 0, w - 1);
                sy = clamp(sy, 0, h - 1);

                int base = src[sy * w + sx];

                // RGB split can move each color in a different direction.
                int rx = clamp(sx + rgbShift, 0, w - 1);
                int ry = clamp(sy - rgbShift / 4, 0, h - 1);
                int bx = clamp(sx - rgbShift, 0, w - 1);
                int by = clamp(sy + rgbShift / 3, 0, h - 1);
                int rSource = src[ry * w + rx];
                int bSource = src[by * w + bx];

                int r = (rSource >> 16) & 0xff;
                int g = (base >> 8) & 0xff;
                int b = bSource & 0xff;

                // Midrange ghost/echo trail.
                if (echoMix > 0.02f) {
                    int ex = clamp(sx - echoShiftX, 0, w - 1);
                    int ey = clamp(sy + echoShiftY, 0, h - 1);
                    int echo = src[ey * w + ex];
                    r = mix(r, (echo >> 16) & 0xff, echoMix);
                    g = mix(g, (echo >> 8) & 0xff, echoMix * 0.72f);
                    b = mix(b, echo & 0xff, echoMix);
                }

                // Saturation and exposure pulse.
                float gray = r * 0.299f + g * 0.587f + b * 0.114f;
                r = clamp(Math.round((gray + (r - gray) * saturation) * brightness), 0, 255);
                g = clamp(Math.round((gray + (g - gray) * saturation) * brightness), 0, 255);
                b = clamp(Math.round((gray + (b - gray) * saturation) * brightness), 0, 255);

                // Continuous hue rotation driven by the spectral balance.
                int hr = clamp(Math.round(hue.rr * r + hue.rg * g + hue.rb * b), 0, 255);
                int hg = clamp(Math.round(hue.gr * r + hue.gg * g + hue.gb * b), 0, 255);
                int hb = clamp(Math.round(hue.br * r + hue.bg * g + hue.bb * b), 0, 255);
                r = hr;
                g = hg;
                b = hb;

                // High-frequency posterization.
                if (posterMix > 0.02f) {
                    int pr = posterize(r, posterLevels);
                    int pg = posterize(g, posterLevels);
                    int pb = posterize(b, posterLevels);
                    r = mix(r, pr, posterMix);
                    g = mix(g, pg, posterMix);
                    b = mix(b, pb, posterMix);
                }

                // Peak-triggered solarize / flash inversion.
                if (solarizeMix > 0.02f) {
                    int sr = r > 132 ? 255 - r : r;
                    int sg = g > 132 ? 255 - g : g;
                    int sb = b > 132 ? 255 - b : b;
                    r = mix(r, sr, solarizeMix);
                    g = mix(g, sg, solarizeMix);
                    b = mix(b, sb, solarizeMix);
                }
                if (invertMix > 0.02f) {
                    r = mix(r, 255 - r, invertMix);
                    g = mix(g, 255 - g, invertMix);
                    b = mix(b, 255 - b, invertMix);
                }

                // Scanlines + dark edge vignette keep the digital look coherent.
                float vignette = 1f - vignetteAmount * Math.min(1f, r2);
                float tone = rowTone[y] * vignette;
                r = clamp(Math.round(r * tone), 0, 255);
                g = clamp(Math.round(g * tone), 0, 255);
                b = clamp(Math.round(b * tone), 0, 255);

                // Fine digital noise, strongest in the treble/chaos region.
                if (noiseAmount > 0.4f) {
                    int n = Math.round((hash01(x, y, seed + 777L) - 0.5f) * noiseAmount * 2f);
                    r = clamp(r + n, 0, 255);
                    g = clamp(g + n, 0, 255);
                    b = clamp(b + n, 0, 255);
                }

                out[y * w + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        return result;
    }

    private static int posterize(int value, int levels) {
        if (levels <= 1) return value;
        float step = 255f / (levels - 1);
        return clamp(Math.round(Math.round(value / step) * step), 0, 255);
    }

    private static int mix(int a, int b, float amount) {
        float t = clamp01(amount);
        return clamp(Math.round(a + (b - a) * t), 0, 255);
    }

    private static float hash01(int x, int y, long seed) {
        long n = x * 0x1f1f1f1fL ^ y * 0x5f356495L ^ seed * 0x27d4eb2dL;
        n ^= (n >>> 15);
        n *= 0x85ebca6bL;
        n ^= (n >>> 13);
        return (n & 0xffffL) / 65535f;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static final class HueMatrix {
        final float rr, rg, rb;
        final float gr, gg, gb;
        final float br, bg, bb;

        HueMatrix(float angle) {
            float c = (float) Math.cos(angle);
            float s = (float) Math.sin(angle);
            rr = 0.213f + c * 0.787f - s * 0.213f;
            rg = 0.715f - c * 0.715f - s * 0.715f;
            rb = 0.072f - c * 0.072f + s * 0.928f;
            gr = 0.213f - c * 0.213f + s * 0.143f;
            gg = 0.715f + c * 0.285f + s * 0.140f;
            gb = 0.072f - c * 0.072f - s * 0.283f;
            br = 0.213f - c * 0.213f - s * 0.787f;
            bg = 0.715f - c * 0.715f + s * 0.715f;
            bb = 0.072f + c * 0.928f + s * 0.072f;
        }
    }
}
