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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
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
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
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
    private ImageCapture imageCapture;
    private TextView audioStatus;
    private TextView sensitivityLabel;
    private Button audioButton;
    private Button shutterButton;
    private float sensitivity = 1.0f;
    private boolean frontCamera = false;
    private ExecutorService photoExecutor;

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
        registerLaunchers();
        buildUi();
        AppLog.i(this, "Main", "App started");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
        uiHandler.post(meterUpdater);
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

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(18), dp(18), dp(18), dp(12));
        top.setBackgroundColor(0x66000000);
        TextView title = text("WARP CAM", 22f, true);
        audioStatus = text("AUDIO  OFF", 12f, false);
        top.addView(title);
        top.addView(audioStatus);
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
        menu.getMenu().add("アップデート確認");
        menu.getMenu().add("ログを書き出す");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.contains("カメラに切替")) {
                frontCamera = !frontCamera;
                startCamera();
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
            return;
        }
        audioStatus.setText(String.format(Locale.US,
                "AUDIO %3d%%   BASS %3d   MID %3d   HIGH %3d",
                Math.round(a.intensity * 100), Math.round(a.bass * 100),
                Math.round(a.mid * 100), Math.round(a.treble * 100)));
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
                CameraSelector selector = frontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, imageCapture);
                AppLog.i(this, "Camera", "Camera bound front=" + frontCamera);
            } catch (Exception e) {
                AppLog.e(this, "Camera", "Camera bind failed", e);
                Toast.makeText(this, "カメラを開始できません", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capture() {
        if (imageCapture == null) return;
        shutterButton.setEnabled(false);
        File temp = new File(getCacheDir(), "capture-" + System.nanoTime() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(temp).build();
        AudioSnapshot capturedAudio = AudioAnalyzer.get().getSnapshot();
        float capturedSensitivity = sensitivity;

        imageCapture.takePicture(options, photoExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                try {
                    Bitmap raw = BitmapFactory.decodeFile(temp.getAbsolutePath());
                    if (raw == null) throw new IllegalStateException("Bitmap decode failed");
                    Bitmap oriented = applyExifOrientation(raw, temp);
                    Bitmap warped = WarpProcessor.process(oriented, capturedAudio, capturedSensitivity);
                    String saved = saveBitmap(warped);
                    AppLog.i(MainActivity.this, "Capture",
                            "Saved=" + saved + " intensity=" + capturedAudio.intensity + " sensitivity=" + capturedSensitivity);
                    if (oriented != raw) raw.recycle();
                    if (warped != oriented) oriented.recycle();
                    warped.recycle();
                    temp.delete();
                    runOnUiThread(() -> {
                        shutterButton.setEnabled(true);
                        Toast.makeText(MainActivity.this,
                                "WARPED  " + Math.round(capturedAudio.intensity * 100) + "%", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    AppLog.e(MainActivity.this, "Capture", "Processing/saving failed", e);
                    temp.delete();
                    runOnUiThread(() -> {
                        shutterButton.setEnabled(true);
                        Toast.makeText(MainActivity.this, "保存に失敗しました", Toast.LENGTH_LONG).show();
                    });
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
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
        if (photoExecutor != null) photoExecutor.shutdownNow();
        super.onDestroy();
    }
}
