package com.ikegami99.warpcam;

import java.util.concurrent.atomic.AtomicReference;

public final class NowPlayingStore {
    private static final AtomicReference<NowPlayingInfo> CURRENT =
            new AtomicReference<>(NowPlayingInfo.empty());

    private NowPlayingStore() {}

    public static NowPlayingInfo get() {
        return CURRENT.get();
    }

    public static void set(NowPlayingInfo info) {
        if (info == null) info = NowPlayingInfo.empty();
        NowPlayingInfo old = CURRENT.getAndSet(info);
        if (old != null && old.artwork != info.artwork) old.recycleArtwork();
    }

    public static NowPlayingInfo snapshotForCapture() {
        return CURRENT.get().snapshotForCapture();
    }
}
