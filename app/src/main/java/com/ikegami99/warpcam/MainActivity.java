package com.ikegami99.warpcam;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private PreviewView previewView;
    private ImageView liveWarpView;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private TextView audioStatus;
    private TextView trackStatus;
    private TextView sensitivityLabel;
    private Button audioButton;
    private Button shutterButton;

    private float sensitivity = 1.0f;
    private boolean frontCamera = false;
    private boolean livePreviewEnabled = true;
    private boolean attachTrackInfo = true;
    private ExecutorService photoExecutor;
    private ExecutorService previewExecutor;
    private Bitmap liveBitmap;
    private long lastLiveFrameNs = 0L;
    private long lastTrackRefreshMs = 0L;

    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<String> audioPermissionLauncher;
    private ActivityResultLauncher<Intent> projectionLauncher;
    private ActivityResultLauncher<String> logExportLauncher;

    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable meterUpdater = new Runnable() {
        @Override public void run() {
            updateAudioUi();
            uiHandler.postDelayed(this, 120);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        photoExecutor = Executors.newSingleThreadExecutor();
        previewExecutor = Executors.newSingleThreadExecutor();
        registerLaunchers();
        buildUi();
        AppLog.i(this, "Main", "App started");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
        NowPlayingTracker.refreshAsync(this);
        uiHandler.post(meterUpdater);
    }

    @Override
    protected void onResume() {
        super.onResume();
        NowPlayingTracker.refreshAsync(this);
    }

    private void registerLaunchers() {
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) startCamera();
                    else Toast.makeText(this, "カメラ権限が必要です", Toast.LENGTH_LONG).show();
                });

        audioPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) requestProjection();
                    else Toast.makeText(this, "内部音声の解析には録音権限が必要です", Toast.LENGTH_LONG).show();
                });

        projectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent service = new Intent(this, AudioReactiveService.class);
                        service.setAction(AudioReactiveService.ACTION_START);
                        service.putExtra(AudioReactiveService.EXTRA_RESULT_CODE, result.getResultCode());
                        service.putExtra(AudioReactiveService.EXTRA_RESULT_DATA, result.getData());
                        ContextCompat.startForegroundService(this, service);
                        Toast.makeText(this, "内部音声への反応を開始します", Toast.LENGTH_SHORT).show();
                    } else {
                        AppLog.i(this, "Audio", "MediaProjection permission denied/cancelled");
                    }
                });

        logExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"),
                uri -> {
                    if (uri == null) return;
                    photoExecutor.execute(() -> {
                        try {
                            AppLog.export(this, uri);
                            runOnUiThread(() -> Toast.makeText(this, "ログを書き出しました", Toast.LENGTH_SHORT).show());
                        } catch (Exception e) {
                            AppLog.e(this, "Log", "Export failed", e);
                            runOnUiThread(() -> Toast.makeText(this, "ログ書き出しに失敗しました", Toast.LENGTH_LONG).show());
                        }
                    });
                });
    }

    private void buildUi() {
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        liveWarpView = new ImageView(this);
        liveWarpView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        liveWarpView.setVisibility(View.INVISIBLE);
        liveWarpView.setBackgroundColor(android.graphics.Color.BLACK);
        root.addView(liveWarpView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(18), dp(18), dp(18), dp(12));
        top.setBackgroundColor(0x66000000);
        TextView title = text("WARP CAM", 22f, true);
        audioStatus = text("AUDIO  OFF", 12f, false);
        trackStatus = text("TRACK  --", 11f, false);
        top.addView(title);
        top.addView(audioStatus);
        top.addView(trackStatus);
        root.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(18), dp(10), dp(18), dp(18));
        controls.setBackgroundColor(0x99000000);

        sensitivityLabel = text("SENSITIVITY  100%", 12f, false);
        controls.addView(sensitivityLabel);

        SeekBar sensitivityBar = new SeekBar(this);
        sensitivityBar.setMax(200);
        sensitivityBar.setProgress(100);
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sensitivity = Math.max(0.05f, progress / 100f);
                sensitivityLabel.setText(String.format(Locale.US, "SENSITIVITY  %d%%", Math.round(sensitivity * 100f)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        controls.addView(sensitivityBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);

        audioButton = button("AUDIO");
        audioButton.setOnClickListener(v -> toggleAudio());
        row.addView(audioButton, weighted(1f));

        shutterButton = button("●");
        shutterButton.setTextSize(30f);
        shutterButton.setOnClickListener(v -> capture());
        row.addView(shutterButton, weighted(1.2f));

        Button more = button("•••");
        more.setOnClickListener(this::showMenu);
        row.addView(more, weighted(1f));

        controls.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(74)));
        root.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(frontCamera ? "背面カメラに切替" : "前面カメラに切替");
        menu.getMenu().add("ライブプレビュー: " + (livePreviewEnabled ? "ON" : "OFF"));
        menu.getMenu().add("曲情報を写真に追加: " + (attachTrackInfo ? "ON" : "OFF"));
        menu.getMenu().add("曲情報アクセス設定");
        menu.getMenu().add("アップデート確認");
        menu.getMenu().add("ログを書き出す");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.contains("カメラに切替")) {
                frontCamera = !frontCamera;
                clearLivePreview();
                startCamera();
            } else if (title.startsWith("ライブプレビュー")) {
                livePreviewEnabled = !livePreviewEnabled;
                if (!livePreviewEnabled) clearLivePreview();
                Toast.makeText(this, livePreviewEnabled ? "ライブプレビュー ON" : "ライブプレビュー OFF", Toast.LENGTH_SHORT).show();
            } else if (title.startsWith("曲情報を写真")) {
                attachTrackInfo = !attachTrackInfo;
                Toast.makeText(this, attachTrackInfo ? "曲情報を写真に追加します" : "曲情報を追加しません", Toast.LENGTH_SHORT).show();
            } else if (title.contains("曲情報アクセス")) {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Exception e) {
                    AppLog.e(this, "NowPlaying", "Could not open notification listener settings", e);
                    Toast.makeText(this, "通知アクセス設定を開けませんでした", Toast.LENGTH_LONG).show();
                }
            } else if (title.contains("アップデート")) {
                UpdateManager.check(this);
            } else if (title.contains("ログ")) {
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                logExportLauncher.launch("warp-cam-" + ts + ".log.txt");
            }
            return true;
        });
        menu.show();
    }

    private void toggleAudio() {
        if (AudioAnalyzer.get().isRunning()) {
            Intent stop = new Intent(this, AudioReactiveService.class);
            stop.setAction(AudioReactiveService.ACTION_STOP);
            startService(stop);
            clearLivePreview();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            requestProjection();
        }
    }

    private void requestProjection() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projectionLauncher.launch(manager.createScreenCaptureIntent());
    }

    private void updateAudioUi() {
        if (audioStatus == null || audioButton == null) return;
        AudioSnapshot a = AudioAnalyzer.get().getSnapshot();
        boolean active = AudioAnalyzer.get().isRunning();
        audioButton.setText(active ? "AUDIO ON" : "AUDIO");
        if (!active) {
            audioStatus.setText("AUDIO  OFF");
            if (liveWarpView != null && liveWarpView.getVisibility() == View.VISIBLE) clearLivePreview();
        } else {
            audioStatus.setText(String.format(Locale.US,
                    "AUDIO %3d%%   BASS %3d   MID %3d   HIGH %3d",
                    Math.round(a.intensity * 100), Math.round(a.bass * 100),
                    Math.round(a.mid * 100), Math.round(a.treble * 100)));
        }

        long now = System.currentTimeMillis();
        if (now - lastTrackRefreshMs > 2000L) {
            lastTrackRefreshMs = now;
            NowPlayingTracker.refreshAsync(this);
        }
        updateTrackUi();
    }

    private void updateTrackUi() {
        if (trackStatus == null) return;
        if (!NowPlayingTracker.hasNotificationAccess(this)) {
            trackStatus.setText("TRACK  通知アクセス未許可");
            return;
        }
        NowPlayingInfo track = NowPlayingStore.get();
        if (!track.hasTrack()) {
            trackStatus.setText("TRACK  曲情報なし");
            return;
        }
        String artist = track.artist.isEmpty() ? "" : "  —  " + track.artist;
        trackStatus.setText("TRACK  " + track.title + artist);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(95)
                        .build();

                imageAnalysis = new ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new Size(640, 480))
                        .build();
                imageAnalysis.setAnalyzer(previewExecutor, this::analyzeLiveFrame);

                CameraSelector selector = frontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                provider.unbindAll();
                try {
                    provider.bindToLifecycle(this, selector, preview, imageCapture, imageAnalysis);
                    AppLog.i(this, "Camera", "Camera bound with live analysis front=" + frontCamera);
                } catch (IllegalArgumentException liveFailure) {
                    AppLog.e(this, "Camera", "Live analysis combination unsupported; falling back to normal preview", liveFailure);
                    imageAnalysis.clearAnalyzer();
                    provider.unbindAll();
                    provider.bindToLifecycle(this, selector, preview, imageCapture);
                    runOnUiThread(() -> Toast.makeText(this,
                            "このカメラではライブ変形を併用できません", Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                AppLog.e(this, "Camera", "Camera bind failed", e);
                Toast.makeText(this, "カメラを開始できません", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeLiveFrame(@NonNull ImageProxy image) {
        if (!livePreviewEnabled || !AudioAnalyzer.get().isRunning()) {
            image.close();
            return;
        }

        long now = System.nanoTime();
        if (now - lastLiveFrameNs < 50_000_000L) {
            image.close();
            return;
        }
        lastLiveFrameNs = now;

        Bitmap raw = null;
        Bitmap oriented = null;
        Bitmap warped = null;
        try {
            int rotation = image.getImageInfo().getRotationDegrees();
            raw = image.toBitmap();
            image.close();
            image = null;

            oriented = orientLiveBitmap(raw, rotation, frontCamera);
            AudioSnapshot audio = AudioAnalyzer.get().getSnapshot();
            warped = WarpProcessor.process(oriented, audio, sensitivity);

            if (oriented != raw && raw != null && !raw.isRecycled()) raw.recycle();
            if (oriented != null && !oriented.isRecycled()) oriented.recycle();

            Bitmap finalWarped = warped;
            runOnUiThread(() -> {
                if (!livePreviewEnabled || !AudioAnalyzer.get().isRunning() || isFinishing() || isDestroyed()) {
                    if (!finalWarped.isRecycled()) finalWarped.recycle();
                    return;
                }
                Bitmap old = liveBitmap;
                liveBitmap = finalWarped;
                liveWarpView.setImageBitmap(finalWarped);
                liveWarpView.setVisibility(View.VISIBLE);
                if (old != null && old != finalWarped && !old.isRecycled()) old.recycle();
            });
        } catch (Throwable t) {
            if (image != null) image.close();
            if (warped != null && !warped.isRecycled()) warped.recycle();
            if (oriented != null && !oriented.isRecycled()) oriented.recycle();
            if (raw != null && !raw.isRecycled()) raw.recycle();
            AppLog.e(this, "LivePreview", "Frame processing failed", t);
        }
    }

    private Bitmap orientLiveBitmap(Bitmap bitmap, int rotationDegrees, boolean mirror) {
        Bitmap current = bitmap;
        if (rotationDegrees != 0) {
            Matrix rotate = new Matrix();
            rotate.postRotate(rotationDegrees);
            current = Bitmap.createBitmap(current, 0, 0, current.getWidth(), current.getHeight(), rotate, true);
        }
        if (mirror) {
            Matrix flip = new Matrix();
            flip.preScale(-1f, 1f);
            Bitmap mirrored = Bitmap.createBitmap(current, 0, 0, current.getWidth(), current.getHeight(), flip, true);
            if (current != bitmap && !current.isRecycled()) current.recycle();
            current = mirrored;
        }
        return current;
    }

    private void clearLivePreview() {
        if (liveWarpView == null) return;
        liveWarpView.setVisibility(View.INVISIBLE);
        liveWarpView.setImageDrawable(null);
        Bitmap old = liveBitmap;
        liveBitmap = null;
        if (old != null && !old.isRecycled()) old.recycle();
    }

    private void capture() {
        if (imageCapture == null) return;
        shutterButton.setEnabled(false);
        File temp = new File(getCacheDir(), "capture-" + System.nanoTime() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(temp).build();
        AudioSnapshot capturedAudio = AudioAnalyzer.get().getSnapshot();
        float capturedSensitivity = sensitivity;
        NowPlayingInfo capturedTrack = attachTrackInfo
                ? NowPlayingStore.snapshotForCapture()
                : NowPlayingInfo.empty();

        imageCapture.takePicture(options, photoExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Bitmap raw = null;
                Bitmap oriented = null;
                Bitmap warped = null;
                Bitmap finalBitmap = null;
                try {
                    raw = BitmapFactory.decodeFile(temp.getAbsolutePath());
                    if (raw == null) throw new IllegalStateException("Bitmap decode failed");
                    oriented = applyExifOrientation(raw, temp);
                    warped = WarpProcessor.process(oriented, capturedAudio, capturedSensitivity);
                    finalBitmap = TrackStripRenderer.append(warped, capturedTrack);
                    String saved = saveBitmap(finalBitmap);
                    AppLog.i(MainActivity.this, "Capture",
                            "Saved=" + saved + " intensity=" + capturedAudio.intensity +
                                    " sensitivity=" + capturedSensitivity +
                                    " track=" + capturedTrack.title + " artist=" + capturedTrack.artist);

                    if (finalBitmap != warped && finalBitmap != null && !finalBitmap.isRecycled()) finalBitmap.recycle();
                    if (warped != null && !warped.isRecycled()) warped.recycle();
                    if (oriented != null && !oriented.isRecycled()) oriented.recycle();
                    if (raw != null && raw != oriented && !raw.isRecycled()) raw.recycle();

                    runOnUiThread(() -> {
                        shutterButton.setEnabled(true);
                        String trackSuffix = capturedTrack.hasTrack() ? "  ♪ " + capturedTrack.title : "";
                        Toast.makeText(MainActivity.this,
                                "WARPED  " + Math.round(capturedAudio.intensity * 100) + "%" + trackSuffix,
                                Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    if (finalBitmap != null && finalBitmap != warped && !finalBitmap.isRecycled()) finalBitmap.recycle();
                    if (warped != null && !warped.isRecycled()) warped.recycle();
                    if (oriented != null && !oriented.isRecycled()) oriented.recycle();
                    if (raw != null && raw != oriented && !raw.isRecycled()) raw.recycle();
                    AppLog.e(MainActivity.this, "Capture", "Processing/saving failed", e);
                    runOnUiThread(() -> {
                        shutterButton.setEnabled(true);
                        Toast.makeText(MainActivity.this, "保存に失敗しました", Toast.LENGTH_LONG).show();
                    });
                } finally {
                    capturedTrack.recycleArtwork();
                    temp.delete();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                capturedTrack.recycleArtwork();
                AppLog.e(MainActivity.this, "Capture", "CameraX capture failed", exception);
                temp.delete();
                runOnUiThread(() -> {
                    shutterButton.setEnabled(true);
                    Toast.makeText(MainActivity.this, "撮影に失敗しました", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private Bitmap applyExifOrientation(Bitmap bitmap, File jpeg) throws Exception {
        ExifInterface exif = new ExifInterface(jpeg);
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.preScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.preScale(1f, -1f); break;
            default: return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private String saveBitmap(Bitmap bitmap) throws Exception {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "WARP_" + ts + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WarpCam");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("MediaStore insert failed");
        try {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("MediaStore output stream was null");
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    throw new IllegalStateException("JPEG compression failed");
                }
                out.flush();
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri.toString();
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
    }

    private TextView text(String value, float sizeSp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(android.graphics.Color.WHITE);
        v.setTextSize(sizeSp);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(android.graphics.Color.WHITE);
        b.setBackgroundColor(0x33000000);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(meterUpdater);
        clearLivePreview();
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
        if (photoExecutor != null) photoExecutor.shutdownNow();
        if (previewExecutor != null) previewExecutor.shutdownNow();
        super.onDestroy();
    }
}
