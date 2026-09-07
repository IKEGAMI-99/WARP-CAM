package com.ikegami99.warpcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class AudioReactiveService extends Service {
    public static final String ACTION_START = "com.ikegami99.warpcam.START_AUDIO";
    public static final String ACTION_STOP = "com.ikegami99.warpcam.STOP_AUDIO";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    private static final int NOTIFICATION_ID = 41;
    private static final String CHANNEL_ID = "warp_audio_capture";

    private MediaProjection mediaProjection;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.projection_notification_title))
                .setContentText(getString(R.string.projection_notification_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        try {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= 33) {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            if (resultData == null) throw new IllegalArgumentException("Missing MediaProjection result data");

            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) throw new IllegalStateException("MediaProjection was null");
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    AudioAnalyzer.get().stop();
                    AppLog.i(AudioReactiveService.this, "AudioService", "MediaProjection stopped by system/user");
                    stopSelf();
                }
            }, null);
            AudioAnalyzer.get().start(mediaProjection);
            AppLog.i(this, "AudioService", "Internal playback capture started");
        } catch (Exception e) {
            AppLog.e(this, "AudioService", "Unable to start internal playback capture", e);
            stopCapture();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }

    private void stopCapture() {
        AudioAnalyzer.get().stop();
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "内部音声解析", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("WARP CAMが再生中の音を解析している間に表示されます");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
