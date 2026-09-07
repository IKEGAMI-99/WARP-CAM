package com.ikegami99.warpcam;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public final class MediaNotificationListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        AppLog.i(this, "NowPlaying", "Notification listener connected");
        NowPlayingTracker.refreshAsync(this);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        AppLog.i(this, "NowPlaying", "Notification listener disconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        NowPlayingTracker.refreshAsync(this);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        NowPlayingTracker.refreshAsync(this);
    }
}
