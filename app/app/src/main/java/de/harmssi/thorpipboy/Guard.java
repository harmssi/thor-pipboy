package de.harmssi.thorpipboy;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Pip-Boy-Wächter ohne eigenen Dauerprozess (spart RAM – Fallout 4 braucht auf dem Thor fast alles).
 * Solange der Pip-Boy läuft, schiebt er alle paar Sekunden einen Alarm DEADLINE_MS in die Zukunft.
 * Beendet Android die App (Low-Memory-Killer beim Laden eines Spielstands), läuft der Alarm ab:
 * dieser Receiver startet kurz, prüft, ob das Plugin (Port 8767) noch lauscht und die Pip-Boy-Task fehlt,
 * und öffnet den Pip-Boy unten neu. Hat der Nutzer ihn selbst geschlossen, passiert nichts.
 * Rechte (einmalig per adb): appops set de.harmssi.thorpipboy SYSTEM_ALERT_WINDOW allow
 *                            appops set de.harmssi.thorpipboy SCHEDULE_EXACT_ALARM allow
 */
public class Guard extends BroadcastReceiver {
    private static final String TAG = "ThorPipBoyGuard";
    static final long DEADLINE_MS = 6000;
    private static final int BOTTOM_DISPLAY = 4;

    static File userClosedFlag(Context ctx) { return new File(ctx.getFilesDir(), "user_closed"); }

    private static PendingIntent pi(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, Guard.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Alarm (neu) setzen – vom laufenden Pip-Boy regelmäßig aufgerufen. */
    static void arm(Context ctx, long delayMs) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        long at = SystemClock.elapsedRealtime() + delayMs;
        try {
            if (am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi(ctx));
            else
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi(ctx));
        } catch (Exception e) {
            Log.w(TAG, "arm: " + e);
        }
    }

    static void disarm(Context ctx) {
        ctx.getSystemService(AlarmManager.class).cancel(pi(ctx));
    }

    @Override public void onReceive(Context ctx, Intent intent) {
        final PendingResult res = goAsync();
        new Thread(() -> {
            try {
                if (userClosedFlag(ctx).exists()) return;
                if (pipBoyTaskAlive(ctx)) { arm(ctx, DEADLINE_MS); return; }  // läuft noch (z. B. pausiert)
                if (!gameListening()) return;                                 // Spiel weg: Kette endet
                Log.i(TAG, "Pip-Boy fehlt bei laufendem Spiel – öffne neu");
                relaunch(ctx);
                arm(ctx, DEADLINE_MS * 3);                                    // falls auch das scheitert
            } finally {
                res.finish();
            }
        }, "pipboy-guard").start();
    }

    private static boolean pipBoyTaskAlive(Context ctx) {
        for (ActivityManager.AppTask t : ctx.getSystemService(ActivityManager.class).getAppTasks()) {
            try {
                ActivityManager.RecentTaskInfo info = t.getTaskInfo();
                if (info.topActivity != null && info.topActivity.getClassName().endsWith("PipBoyActivity")) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static boolean gameListening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(PipBoyActivity.HOST, PipBoyActivity.PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void relaunch(Context ctx) {
        Intent pip = new Intent(ctx, PipBoyActivity.class);
        pip.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int display = 0;
        for (Display d : ctx.getSystemService(DisplayManager.class).getDisplays())
            if (d.getDisplayId() == BOTTOM_DISPLAY) display = BOTTOM_DISPLAY;
        try {
            ctx.startActivity(pip, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
        } catch (Exception e) {
            Log.w(TAG, "relaunch: " + e);
        }
    }
}
