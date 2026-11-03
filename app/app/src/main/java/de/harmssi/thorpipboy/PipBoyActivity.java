package de.harmssi.thorpipboy;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import org.json.JSONObject;

/**
 * Pip-Boy auf dem unteren Bildschirm. Das Fenster nimmt keinen Tastatur-/Controller-Fokus an,
 * damit der Controller beim Spiel oben bleibt; Touch funktioniert trotzdem.
 */
public class PipBoyActivity extends Activity implements WsClient.Listener {
    static final String HOST = "127.0.0.1";
    static final int PORT = 8767;

    private PipBoyView view;
    private WsClient client;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Wächter-Alarm laufend nach vorn schieben (auch im Hintergrund, solange der Prozess lebt). */
    private final Runnable rearm = new Runnable() {
        @Override public void run() {
            Guard.arm(PipBoyActivity.this, Guard.DEADLINE_MS);
            handler.postDelayed(this, Guard.DEADLINE_MS / 3);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Guard.userClosedFlag(this).delete();
        handler.post(rearm);
        client = new WsClient(HOST, PORT, this);
        view = new PipBoyView(this, client);
        setContentView(view);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /** Test-Haken: "am start -n de.harmssi.thorpipboy/.PipBoyActivity --ez debug_kill true" beendet den
     *  UI-Prozess hart (ohne onDestroy), wie es der Low-Memory-Killer tut – zum Prüfen des Wächters. */
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("debug_kill", false)) android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override protected void onStart() { super.onStart(); client.start(); }
    @Override protected void onStop() { client.stop(); super.onStop(); }

    @Override protected void onDestroy() {
        // bewusst geschlossen (nicht von Android beendet): Wächter soll ihn nicht wieder öffnen
        if (isFinishing() && !isChangingConfigurations()) {
            try { Guard.userClosedFlag(this).createNewFile();
            Guard.disarm(this);
            handler.removeCallbacks(rearm); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override public void onConnection(boolean connected) {
        view.setConnected(connected);
        if (connected) subscribe();
    }

    @Override public void onMessage(JSONObject m) {
        view.onMessage(m);
    }

    /** Abos wie im Protokoll des Plugins (README im Plugin-Repo). */
    private void subscribe() {
        client.subscribe("main", 400, "game", "player.stats", "player.special", "player.limbs",
                "player.location", "world.time");
        client.subscribe("inv", 1000, "player.inventory");
        client.subscribe("quests", 2000, "player.quests", "pipboy.Quests");
        client.subscribe("radio", 1500, "radio");
        client.subscribe("map", 2500, "pipboy.Map");
    }
}
