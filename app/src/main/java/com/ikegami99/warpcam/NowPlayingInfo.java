package com.ikegami99.warpcam;

import android.graphics.Bitmap;

public final class NowPlayingInfo {
    public final String title;
    public final String artist;
    public final String album;
    public final String packageName;
    public final Bitmap artwork;
    public final long timestampMs;

    public NowPlayingInfo(String title, String artist, String album, String packageName, Bitmap artwork, long timestampMs) {
        this.title = clean(title);
        this.artist = clean(artist);
        this.album = clean(album);
        this.packageName = clean(packageName);
        this.artwork = artwork;
        this.timestampMs = timestampMs;
    }

    public static NowPlayingInfo empty() {
        return new NowPlayingInfo("", "", "", "", null, System.currentTimeMillis());
    }

    public boolean hasTrack() {
        return !title.isEmpty() || !artist.isEmpty();
    }

    public NowPlayingInfo snapshotForCapture() {
        Bitmap copy = null;
        if (artwork != null && !artwork.isRecycled()) {
            try {
                Bitmap.Config cfg = artwork.getConfig() != null ? artwork.getConfig() : Bitmap.Config.ARGB_8888;
                copy = artwork.copy(cfg, false);
            } catch (Throwable ignored) {}
        }
        return new NowPlayingInfo(title, artist, album, packageName, copy, timestampMs);
    }

    public void recycleArtwork() {
        if (artwork != null && !artwork.isRecycled()) artwork.recycle();
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
