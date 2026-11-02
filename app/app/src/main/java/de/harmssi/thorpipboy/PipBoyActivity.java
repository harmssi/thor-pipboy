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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        client = new WsClient(HOST, PORT, this);
        view = new PipBoyView(this, client);
        setContentView(view);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override protected void onStart() { super.onStart(); client.start(); }
    @Override protected void onStop() { client.stop(); super.onStop(); }

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
        client.subscribe("quests", 2000, "player.quests");
        client.subscribe("radio", 1500, "radio");
        client.subscribe("map", 2500, "pipboy.Map");
    }
}
