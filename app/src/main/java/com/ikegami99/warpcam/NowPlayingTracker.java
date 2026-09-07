package com.ikegami99.warpcam;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;

import androidx.core.app.NotificationManagerCompat;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NowPlayingTracker {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private NowPlayingTracker() {}

    public static boolean hasNotificationAccess(Context context) {
        Set<String> enabled = NotificationManagerCompat.getEnabledListenerPackages(context);
        return enabled.contains(context.getPackageName());
    }

    public static void refreshAsync(Context context) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> refresh(app));
    }

    public static void refresh(Context context) {
        if (!hasNotificationAccess(context)) {
            NowPlayingStore.set(NowPlayingInfo.empty());
            return;
        }
        try {
            MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName listener = new ComponentName(context, MediaNotificationListener.class);
            List<MediaController> controllers = manager.getActiveSessions(listener);
            MediaController chosen = null;
            for (MediaController controller : controllers) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && (state.getState() == PlaybackState.STATE_PLAYING ||
                        state.getState() == PlaybackState.STATE_BUFFERING ||
                        state.getState() == PlaybackState.STATE_CONNECTING)) {
                    chosen = controller;
                    break;
                }
            }
            if (chosen == null) {
                NowPlayingStore.set(NowPlayingInfo.empty());
                return;
            }

            MediaMetadata metadata = chosen.getMetadata();
            if (metadata == null) {
                NowPlayingStore.set(NowPlayingInfo.empty());
                return;
            }
            MediaDescription description = metadata.getDescription();
            String title = first(text(description.getTitle()), metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            String artist = first(text(description.getSubtitle()),
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
            String album = first(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM), text(description.getDescription()));

            Bitmap artwork = copyArtwork(description.getIconBitmap());
            if (artwork == null) artwork = copyArtwork(metadata.getBitmap(MediaMetadata.METADATA_KEY_ART));
            if (artwork == null) artwork = copyArtwork(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART));
            if (artwork == null && description.getIconUri() != null) artwork = loadUri(context, description.getIconUri());
            if (artwork == null) artwork = loadAppIcon(context, chosen.getPackageName());

            NowPlayingStore.set(new NowPlayingInfo(
                    title, artist, album, chosen.getPackageName(), artwork, System.currentTimeMillis()));
            AppLog.i(context, "NowPlaying", "Track=" + title + " artist=" + artist + " app=" + chosen.getPackageName());
        } catch (SecurityException e) {
            NowPlayingStore.set(NowPlayingInfo.empty());
            AppLog.e(context, "NowPlaying", "Notification/media-session access not granted", e);
        } catch (Throwable t) {
            AppLog.e(context, "NowPlaying", "Failed to read active media session", t);
        }
    }

    private static Bitmap loadUri(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            Bitmap decoded = android.graphics.BitmapFactory.decodeStream(in);
            Bitmap copy = copyArtwork(decoded);
            if (decoded != null && decoded != copy && !decoded.isRecycled()) decoded.recycle();
            return copy;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap loadAppIcon(Context context, String packageName) {
        try {
            Drawable drawable = context.getPackageManager().getApplicationIcon(packageName);
            return drawableToBitmap(drawable, 384);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable, int maxSize) {
        if (drawable instanceof BitmapDrawable) {
            return copyArtwork(((BitmapDrawable) drawable).getBitmap());
        }
        int iw = Math.max(1, drawable.getIntrinsicWidth());
        int ih = Math.max(1, drawable.getIntrinsicHeight());
        float scale = Math.min(1f, maxSize / (float) Math.max(iw, ih));
        int w = Math.max(1, Math.round(iw * scale));
        int h = Math.max(1, Math.round(ih * scale));
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);
        return bitmap;
    }

    private static Bitmap copyArtwork(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        float scale = Math.min(1f, 512f / Math.max(w, h));
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        try {
            if (nw != w || nh != h) return Bitmap.createScaledBitmap(src, nw, nh, true);
            Bitmap.Config config = src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888;
            return src.copy(config, false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
