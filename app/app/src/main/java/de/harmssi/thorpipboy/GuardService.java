package de.harmssi.thorpipboy;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Pip-Boy-Wächter (eigener Prozess ":guard", Foreground-Service).
 * Läuft Fallout 4 mit dem Plugin (Port 8767 offen) und fehlt die Pip-Boy-Task – etwa weil Android
 * die App beim Laden eines Spielstands wegen Speichermangel beendet hat –, öffnet er sie unten neu.
 * Hat der Nutzer den Pip-Boy selbst geschlossen, bleibt er zu, bis das Spiel beendet ist.
 * Für den Start aus dem Hintergrund braucht die App "Über anderen Apps einblenden"
 * (adb: appops set de.harmssi.thorpipboy SYSTEM_ALERT_WINDOW allow).
 */
public class GuardService extends Service {
    private static final String TAG = "ThorPipBoyGuard";
    private static final int BOTTOM_DISPLAY = 4;
    private static final long IDLE_STOP_MS = 10 * 60 * 1000;   // ohne Spiel nach 10 min beenden
    private volatile boolean running;
    private Thread thread;

    static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, GuardService.class));
        } catch (Exception e) {
            Log.w(TAG, "start: " + e);
        }
    }

    static File userClosedFlag(Context ctx) { return new File(ctx.getFilesDir(), "user_closed"); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("guard", getString(R.string.guard_channel), NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "guard")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(getString(R.string.guard_title))
                .setOngoing(true).build();
        startForeground(1, n);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            thread = new Thread(this::loop, "pipboy-guard");
            thread.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (thread != null) thread.interrupt();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void loop() {
        long lastGame = SystemClock.elapsedRealtime();
        while (running) {
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            boolean ui = pipBoyTaskAlive();
            boolean game = gameListening();
            long now = SystemClock.elapsedRealtime();
            if (game) lastGame = now;
            else userClosedFlag(this).delete();          // Spiel weg: Nutzerwunsch zurücksetzen
            if (game && !ui && !userClosedFlag(this).exists()) {
                Log.i(TAG, "Pip-Boy fehlt bei laufendem Spiel – öffne neu");
                relaunch();
            }
            if (!game && now - lastGame > IDLE_STOP_MS) {
                Log.i(TAG, "kein Spiel seit 10 min – Wächter beendet sich");
                stopSelf();
                return;
            }
        }
    }

    private boolean pipBoyTaskAlive() {
        ActivityManager am = getSystemService(ActivityManager.class);
        for (ActivityManager.AppTask t : am.getAppTasks()) {
            try {
                ActivityManager.RecentTaskInfo info = t.getTaskInfo();
                if (info.topActivity != null && info.topActivity.getClassName().endsWith("PipBoyActivity")) return true;
                if (info.baseActivity != null && info.baseActivity.getClassName().endsWith("PipBoyActivity")) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private boolean gameListening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(PipBoyActivity.HOST, PipBoyActivity.PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void relaunch() {
        Intent pip = new Intent(this, PipBoyActivity.class);
        pip.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int display = 0;
        for (Display d : getSystemService(DisplayManager.class).getDisplays())
            if (d.getDisplayId() == BOTTOM_DISPLAY) display = BOTTOM_DISPLAY;
        try {
            startActivity(pip, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
        } catch (Exception e) {
            Log.w(TAG, "relaunch: " + e);
        }
    }
}
