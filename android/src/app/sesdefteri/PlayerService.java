package app.sesdefteri;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;

/** Media notification + foreground service so music keeps playing with the screen off. */
public class PlayerService extends Service {
    static final String CHANNEL = "playback";
    static final int ID = 8;
    static final String A_UPDATE = "app.videodefteri.UPDATE";
    static final String A_TOGGLE = "app.videodefteri.TOGGLE";
    static final String A_NEXT = "app.videodefteri.NEXT";
    static final String A_PREV = "app.videodefteri.PREV";
    static final String A_STOP = "app.videodefteri.STOP";

    private static boolean running;

    static void update(Context c, boolean playing) {
        if (c == null) return;
        Intent i = new Intent(c, PlayerService.class).setAction(A_UPDATE);
        try {
            if (playing) {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } else if (running) {
                c.startService(i);
            }
        } catch (Exception e) {
            try {
                NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && running) nm.notify(ID, build(c));
            } catch (Exception ignored) { }
        }
    }

    static void stop(Context c) {
        if (c == null) return;
        try { c.stopService(new Intent(c, PlayerService.class)); } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        String a = intent != null ? intent.getAction() : null;
        Player p = Player.I;
        if (A_TOGGLE.equals(a)) p.toggle();
        else if (A_NEXT.equals(a)) p.next();
        else if (A_PREV.equals(a)) p.prev();
        Notification n = build(this);
        try {
            // a service started with startForegroundService must always enter the foreground first
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, n);
        } catch (Exception ignored) { }
        if (A_STOP.equals(a)) {
            p.stop();
            try {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
                else stopForeground(true);
            } catch (Exception ignored) { }
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (!p.isPlaying()) {
                // keep the notification so playback can resume, but let the user swipe it away
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
                else stopForeground(false);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(ID, n);
            }
        } catch (Exception ignored) { }
        return START_NOT_STICKY;
    }

    private static PendingIntent action(Context c, String a, int code) {
        Intent i = new Intent(c, PlayerService.class).setAction(a);
        int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(c, code, i, fl);
        return PendingIntent.getService(c, code, i, fl);
    }

    static Notification build(Context c) {
        Player p = Player.I;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, Str.get(c, "chPlay"), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c);
        }
        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean playing = p.isPlaying();
        b.setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(p.title())
            .setContentText(p.subtitle())
            .setContentIntent(pi)
            .setDeleteIntent(action(c, A_STOP, 5))
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC);
        Bitmap art = p.art();
        if (art != null) b.setLargeIcon(art);
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, Str.get(c, "prev"), action(c, A_PREV, 1)).build());
        b.addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
            Str.get(c, playing ? "pause" : "play"), action(c, A_TOGGLE, 2)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, Str.get(c, "next"), action(c, A_NEXT, 3)).build());
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (p.token() != null) style.setMediaSession(p.token());
        b.setStyle(style);
        return b.build();
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) { }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
