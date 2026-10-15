package io.github.tsik2011dev.takken;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

/** Keeps playback alive in the background and shows the media notification (lock screen / notification shade controls). */
public class PlayerService extends Service {
    static volatile PlayerService S;
    private static final String CH = "playback";
    private static final int ID = 1;

    @Override public void onCreate() {
        super.onCreate();
        S = this;
        NotificationChannel ch = new NotificationChannel(CH, "再生", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Player.get(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Player p = Player.get(this);
        String a = intent != null ? intent.getAction() : null;
        if (a == null) {
            goForeground();          // started with startForegroundService(): must call startForeground()
            if (!(p.playing || p.loading)) refresh();
        } else if ("PLAY_PAUSE".equals(a)) {
            p.toggle();
        } else if ("NEXT".equals(a)) {
            p.next();
        } else if ("PREV".equals(a)) {
            p.prev();
        } else if ("DISMISS".equals(a)) {
            if (!p.playing && !p.loading) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        }
        return START_NOT_STICKY;
    }

    private void goForeground() {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(ID, n);
    }

    /** Called by Player whenever the playback state changes. */
    void refresh() {
        Player p = Player.get(this);
        try {
            if (p.playing || p.loading) {
                goForeground();
            } else {
                stopForeground(STOP_FOREGROUND_DETACH);
                if (p.current() != null) getSystemService(NotificationManager.class).notify(ID, build());
            }
        } catch (Exception ignored) { }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        Player p = Player.get(this);
        if (!p.playing && !p.loading) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        // while playing, keep going: the service holds the process in the foreground
    }

    @Override public void onDestroy() {
        S = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private PendingIntent svc(String action, int rc) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, rc, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    Notification build() {
        Player p = Player.get(this);
        Player.Track t = p.current();
        String title = t != null ? t.title : "宅建読み上げ";
        String text = p.loading ? "音声を準備しています…" : (t != null ? t.chap : "");
        boolean on = p.playing || p.loading;
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setLargeIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(svc("DISMISS", 4))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(on)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "前へ", svc("PREV", 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, on ? R.drawable.ic_pause : R.drawable.ic_play), on ? "一時停止" : "再生", svc("PLAY_PAUSE", 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "次へ", svc("NEXT", 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(p.session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
