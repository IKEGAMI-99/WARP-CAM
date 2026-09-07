package com.ikegami99.warpcam;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class UpdateManager {
    private static final String RELEASES_API = "https://api.github.com/repos/IKEGAMI-99/WARP-CAM/releases/latest";
    private static final String APK_NAME = "warp-cam-release.apk";

    private UpdateManager() {}

    public static void check(Context context) {
        Toast.makeText(context, "アップデートを確認中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject release = new JSONObject(getText(RELEASES_API));
                String tag = release.optString("tag_name", "");
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) {
                    toast(context, "最新版です  v" + BuildConfig.VERSION_NAME);
                    return;
                }

                String apkUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (APK_NAME.equals(asset.optString("name"))) {
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                if (apkUrl == null) throw new IllegalStateException("Release asset " + APK_NAME + " not found");

                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.getPackageName()));
                    settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(settings);
                    toast(context, "このアプリからのインストールを許可してから、もう一度更新を確認してください");
                    return;
                }

                toast(context, "新しい " + tag + " をダウンロード中…");
                File dir = new File(context.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create update directory");
                File apk = new File(dir, APK_NAME);
                download(apkUrl, apk);
                if (apk.length() < 50_000) throw new IllegalStateException("Downloaded APK is unexpectedly small: " + apk.length());
                AppLog.i(context, "Update", "Downloaded " + tag + " bytes=" + apk.length());
                install(context, apk);
            } catch (Exception e) {
                AppLog.e(context, "Update", "Update check/download failed", e);
                toast(context, "アップデートに失敗しました: " + e.getMessage());
            }
        }, "WarpUpdate").start();
    }

    private static String getText(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "WARP-CAM/" + BuildConfig.VERSION_NAME);
        try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }

    private static void download(String url, File file) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "WARP-CAM/" + BuildConfig.VERSION_NAME);
        try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(file, false)) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
            out.flush();
            out.getFD().sync();
        } finally {
            conn.disconnect();
        }
    }

    private static void install(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    static boolean isNewer(String tag, String current) {
        int[] remote = parseVersion(tag);
        int[] local = parseVersion(current);
        for (int i = 0; i < 3; i++) {
            if (remote[i] != local[i]) return remote[i] > local[i];
        }
        return false;
    }

    private static int[] parseVersion(String value) {
        String cleaned = value == null ? "" : value.trim().replaceFirst("^[vV]", "");
        String[] parts = cleaned.split("[-+.]");
        int[] result = new int[] {0, 0, 0};
        if (parts.length > 0) {
            String[] nums = parts[0].split("\\.");
            for (int i = 0; i < Math.min(3, nums.length); i++) {
                try { result[i] = Integer.parseInt(nums[i].replaceAll("[^0-9]", "")); }
                catch (Exception ignored) {}
            }
        }
        return result;
    }

    private static void toast(Context context, String message) {
        new android.os.Handler(context.getMainLooper()).post(
                () -> Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        );
    }
}
