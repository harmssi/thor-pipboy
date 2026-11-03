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
 * dieser Receiver startet kurz, fragt den Plugin-Status ab (HTTP auf Port 8767) und öffnet den Pip-Boy
 * unten neu, sobald das Spiel wieder STABIL läuft (Spielstand seit >= 20 s geladen).
 * Wichtig (am Gerät gemessen): Holt sich der Pip-Boy den Vordergrund, fällt DroiDeck von oom_adj 0 auf 50;
 * während eines Lade-Peaks beendet der Low-Memory-Killer dann DroiDeck – und damit das Spiel.
 * Deshalb nie während des Ladens neu öffnen. Hat der Nutzer den Pip-Boy selbst geschlossen, passiert nichts.
 * Rechte (einmalig per adb): appops set de.harmssi.thorpipboy SYSTEM_ALERT_WINDOW allow
 *                            appops set de.harmssi.thorpipboy SCHEDULE_EXACT_ALARM allow
 */
public class Guard extends BroadcastReceiver {
    private static final String TAG = "ThorPipBoyGuard";
    static final long DEADLINE_MS = 6000;
    private static final long STABLE_MS = 20000;   // so lange muss der Spielstand geladen sein
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
                long readyFor = gameReadyForMs();
                File gone = new File(ctx.getFilesDir(), "game_gone_since");
                if (readyFor < 0) {
                    // Spiel nicht erreichbar (z. B. Neustart nach Absturz): bis 3 min weiter prüfen, dann Kette beenden
                    long now = System.currentTimeMillis();
                    if (!gone.exists()) { try { gone.createNewFile(); } catch (Exception ignored) { } }
                    if (now - gone.lastModified() < 180000) arm(ctx, 10000);
                    else gone.delete();
                    return;
                }
                gone.delete();
                if (readyFor < STABLE_MS) {                                   // lädt noch: später erneut prüfen
                    Log.i(TAG, "Pip-Boy fehlt, Spiel noch nicht stabil (" + readyFor + " ms) – warte");
                    arm(ctx, 5000);
                    return;
                }
                Log.i(TAG, "Pip-Boy fehlt bei stabil laufendem Spiel – öffne neu");
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

    /** Plugin-Status per HTTP: -1 = Spiel/Plugin nicht erreichbar, sonst ms seit geladenem Spielstand (0 = lädt). */
    private static long gameReadyForMs() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(PipBoyActivity.HOST, PipBoyActivity.PORT), 500);
            s.setSoTimeout(1000);
            s.getOutputStream().write("GET /status HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = s.getInputStream().read(buf)) > 0) b.write(buf, 0, n);
            String r = b.toString("UTF-8");
            org.json.JSONObject j = new org.json.JSONObject(r.substring(r.indexOf('{')));
            return j.optBoolean("ready") ? j.optLong("readyForMs", 0) : 0;
        } catch (Exception e) {
            return -1;
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
