package com.ikegami99.warpcam;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AppLog {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "warp-cam.log";

    private AppLog() {}

    public static void i(Context context, String tag, String message) {
        write(context, "I", tag, message, null);
    }

    public static void e(Context context, String tag, String message, Throwable error) {
        write(context, "E", tag, message, error);
    }

    private static void write(Context context, String level, String tag, String message, Throwable error) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), FILE_NAME);
                boolean newFile = !file.exists() || file.length() == 0;
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    if (newFile) out.write(header().getBytes(StandardCharsets.UTF_8));
                    String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                    out.write((ts + " " + level + "/" + tag + ": " + safe(message) + "\n").getBytes(StandardCharsets.UTF_8));
                    if (error != null) {
                        out.write((error.getClass().getName() + ": " + safe(error.getMessage()) + "\n").getBytes(StandardCharsets.UTF_8));
                        for (StackTraceElement element : error.getStackTrace()) {
                            out.write(("    at " + element + "\n").getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    out.flush();
                    out.getFD().sync();
                }
            } catch (Exception ignored) {}
        }
    }

    public static void export(Context context, Uri uri) throws Exception {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            byte[] payload;
            if (file.exists() && file.length() > 0) {
                try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (n > 0) bytes.write(buffer, 0, n);
                    }
                    payload = bytes.toByteArray();
                }
            } else {
                payload = (header() + "No runtime log entries were recorded before export.\n")
                        .getBytes(StandardCharsets.UTF_8);
            }
            if (payload.length == 0) {
                payload = (header() + "Log export fallback.\n").getBytes(StandardCharsets.UTF_8);
            }
            try (OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("Unable to open export destination");
                out.write(payload);
                out.flush();
            }
        }
    }

    private static String header() {
        return "WARP CAM LOG\n" +
                "app=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n" +
                "android=" + Build.VERSION.RELEASE + " api=" + Build.VERSION.SDK_INT + "\n" +
                "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n---\n";
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
