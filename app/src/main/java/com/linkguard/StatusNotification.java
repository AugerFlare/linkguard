package com.linkguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Optional always-there notification showing whether protection is on, with a Pause/Resume button.
 * The receiver half handles that button.
 */
public class StatusNotification extends BroadcastReceiver {

    private static final String CHANNEL = "protection";
    private static final int ID = 2;

    // ponytail: plain ongoing notification, not a foreground service. Android 14+ lets users swipe it
    // away; it comes back on the next intercepted link or app open. Use a service if that's not enough.
    static void update(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        // Channels from earlier builds: per-link "cleaned" (now toasts), "status" (was MIN, which hid the Pause button).
        nm.deleteNotificationChannel("cleaned");
        nm.deleteNotificationChannel("status");
        if (!shown(c)) { nm.cancel(ID); return; }

        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Protection status", NotificationManager.IMPORTANCE_LOW));
        boolean on = MainActivity.enabled(c);
        PendingIntent openApp = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getBroadcast(c, 0,
                new Intent(c, StatusNotification.class), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(ID, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(on ? R.drawable.ic_shield : R.drawable.ic_warning)
                .setContentTitle(on ? "Protection on" : "Protection paused")
                .setContentText(on ? "Links are checked and cleaned." : "Links open unchecked.")
                .setContentIntent(openApp)
                .addAction(new Notification.Action.Builder(null, on ? "Pause" : "Resume", toggle).build())
                .setOngoing(true)
                .setShowWhen(false)
                .build());
    }

    static boolean shown(Context c) {
        return MainActivity.prefs(c).getBoolean("statusNotification", false);
    }

    static void setShown(Context c, boolean shown) {
        MainActivity.prefs(c).edit().putBoolean("statusNotification", shown).apply();
        update(c);
    }

    /** Pause/Resume button. */
    @Override
    public void onReceive(Context c, Intent i) {
        MainActivity.prefs(c).edit().putBoolean("enabled", !MainActivity.enabled(c)).apply();
        update(c);
    }
}
