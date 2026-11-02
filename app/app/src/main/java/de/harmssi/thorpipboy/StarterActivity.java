package de.harmssi.thorpipboy;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

/**
 * Startet Fallout 4 (Steam-AppID 377160) in DroiDeck auf dem oberen Bildschirm (Display 0)
 * und öffnet den Pip-Boy auf dem unteren Bildschirm (beim AYN Thor Display 4).
 */
public class StarterActivity extends Activity {
    static final String STEAM_APP_ID = "377160";
    private static final int TOP_DISPLAY = 0;
    private static final int BOTTOM_DISPLAY = 4;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent dd = new Intent("com.droiddeck.launcher.LAUNCH_GAME");
        dd.setClassName("com.droiddeck.launcher", "com.droiddeck.launcher.FrontEndActivity");
        dd.putExtra("app_id", STEAM_APP_ID);
        dd.putExtra("game_source", "STEAM");
        dd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(dd, ActivityOptions.makeBasic().setLaunchDisplayId(TOP_DISPLAY).toBundle());
        } catch (Exception e) {
            Log.w("ThorPipBoy", "DroiDeck: " + e);
            Toast.makeText(this, R.string.droiddeck_missing, Toast.LENGTH_LONG).show();
        }
        Intent pip = new Intent(this, PipBoyActivity.class);
        pip.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(pip, ActivityOptions.makeBasic().setLaunchDisplayId(bottomDisplay()).toBundle());
        finish();
    }

    /** Display 4 auf dem Thor; auf anderen Geräten das erste Nicht-Standard-Display, sonst 0. */
    private int bottomDisplay() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        int fallback = TOP_DISPLAY;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == BOTTOM_DISPLAY) return BOTTOM_DISPLAY;
            if (d.getDisplayId() != TOP_DISPLAY && fallback == TOP_DISPLAY) fallback = d.getDisplayId();
        }
        return fallback;
    }
}
