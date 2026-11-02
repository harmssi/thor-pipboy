package de.harmssi.thorpipboy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Eigene Pip-Boy-Optik: grüner Phosphor-Monochrom-Bildschirm mit Scanlines, alles per Canvas gezeichnet
 * (keine Spielgrafiken). Reiter STAT/INV/DATEN/KARTE/RADIO, Tippen auf Gegenstände = ausrüsten/benutzen.
 */
public class PipBoyView extends View {
    // ---- Farben ----
    static final int G = 0xFF1AFF80;          // Phosphor-Grün
    static final int G_DIM = 0x661AFF80;
    static final int G_FAINT = 0x2A1AFF80;
    static final int BG = 0xFF040C06;
    static final int BG_HI = 0xFF0C2A16;

    private final WsClient client;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap overlay;   // Scanlines + Vignette

    // ---- Zustand aus dem Spiel ----
    private boolean connected;
    private boolean ready;
    private JSONObject game, stats, limbs, location, time, radio, map;
    private JSONArray special, inventory, quests, pipQuests;
    private final Map<String, String> pending = new HashMap<>();   // cmdId -> Name
    private String toast;
    private long toastUntil;

    // ---- UI ----
    private int tab;                              // 0..4
    private final int[] sub = new int[5];
    private final Map<String, Float> scroll = new HashMap<>();
    private String scrollKey = "";
    private float contentMaxScroll;
    private RectF contentRect = new RectF();
    private boolean mapZoom;

    private static class Hit {
        final RectF r; final Runnable action;
        Hit(RectF r, Runnable a) { this.r = r; this.action = a; }
    }
    private final List<Hit> hits = new ArrayList<>();

    public PipBoyView(Context ctx, WsClient client) {
        super(ctx);
        this.client = client;
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        text.setTypeface(mono);
        text.setColor(G);
        line.setColor(G);
        line.setStyle(Paint.Style.STROKE);
        fill.setColor(G);
        glow.setColor(G);
        glow.setMaskFilter(new BlurMaskFilter(10, BlurMaskFilter.Blur.NORMAL));
        glow.setTypeface(mono);
        setBackgroundColor(BG);
    }

    // =====================================================================
    // Daten
    // =====================================================================
    public void setConnected(boolean c) {
        connected = c;
        if (!c) ready = false;
        invalidate();
    }

    public void onMessage(JSONObject m) {
        String type = m.optString("type");
        switch (type) {
            case "hello":
                ready = m.optBoolean("ready");
                break;
            case "data": {
                JSONObject d = m.optJSONObject("data");
                if (d == null) break;
                if (d.has("game")) {
                    game = d.optJSONObject("game");
                    ready = game != null && game.optBoolean("ready") && game.optBoolean("player");
                }
                if (d.has("player.stats")) stats = d.optJSONObject("player.stats");
                if (d.has("player.special")) special = d.optJSONArray("player.special");
                if (d.has("player.limbs")) limbs = d.optJSONObject("player.limbs");
                if (d.has("player.location")) location = d.optJSONObject("player.location");
                if (d.has("world.time")) time = d.optJSONObject("world.time");
                if (d.has("player.inventory")) inventory = d.optJSONArray("player.inventory");
                if (d.has("player.quests")) quests = d.optJSONArray("player.quests");
                if (d.has("radio")) radio = d.optJSONObject("radio");
                if (d.has("pipboy.Map")) map = d.optJSONObject("pipboy.Map");
                if (d.has("pipboy.Quests")) pipQuests = d.optJSONArray("pipboy.Quests");
                break;
            }
            case "commandResult": {
                String name = pending.remove(m.optString("id"));
                if (name == null) name = "";
                if (!m.optBoolean("success")) {
                    showToast(getContext().getString(R.string.failed, m.optString("error")));
                } else {
                    JSONObject r = m.optJSONObject("result");
                    String a = r != null ? r.optString("action") : "";
                    if (a.equals("used")) showToast(getContext().getString(R.string.used, name));
                    else if (a.equals("unequipped")) showToast(getContext().getString(R.string.unequipped, name));
                    else if (a.equals("equipped") || a.equals("already_equipped")) showToast(getContext().getString(R.string.equipped, name));
                    else if (m.optString("command").equals("radio") && r != null)
                        showToast(getContext().getString(r.optBoolean("enabled") ? R.string.radio_on : R.string.radio_off));
                }
                break;
            }
            default:
                break;
        }
        invalidate();
    }

    private void showToast(String s) {
        toast = s;
        toastUntil = SystemClock.uptimeMillis() + 2500;
        postInvalidateDelayed(2600);
    }

    // =====================================================================
    // Zeichnen
    // =====================================================================
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(overlay);
        Paint p = new Paint();
        p.setColor(0x22000000);
        for (int y = 0; y < h; y += 3) c.drawRect(0, y, w, y + 1, p);
        p.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.72f,
                new int[]{0x00000000, 0x00000000, 0x99000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    private float u;   // Grundeinheit (Textgröße)

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        u = Math.min(w, h) / 30f;
        hits.clear();
        // leichter Phosphor-Schimmer im Hintergrund
        fill.setShader(new LinearGradient(0, 0, 0, h, 0xFF06160B, 0xFF020703, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        float pad = u * 1.2f;
        if (!connected || !ready) {
            drawWaiting(c, w, h);
        } else {
            float top = pad;
            float tabsH = u * 2.4f;
            drawTabs(c, pad, top, w - pad, tabsH);
            float subTop = top + tabsH + u * 0.3f;
            float subH = u * 2.0f;
            drawSubTabs(c, pad, subTop, w - pad, subH);
            float footH = u * 2.4f;
            contentRect = new RectF(pad, subTop + subH + u * 0.6f, w - pad, h - pad - footH - u * 0.6f);
            c.save();
            c.clipRect(contentRect);
            switch (tab) {
                case 0: drawStat(c, contentRect); break;
                case 1: drawInv(c, contentRect); break;
                case 2: drawData(c, contentRect); break;
                case 3: drawMap(c, contentRect); break;
                case 4: drawRadio(c, contentRect); break;
            }
            c.restore();
            drawFooter(c, pad, h - pad - footH, w - pad, footH);
            if (toast != null && SystemClock.uptimeMillis() < toastUntil) drawToast(c, w, contentRect.bottom);
        }
        if (overlay != null) c.drawBitmap(overlay, 0, 0, null);
        if (tab == 4 && radio != null && radio.optBoolean("enabled") && connected && ready) postInvalidateDelayed(50);
    }

    private void glowText(Canvas c, String s, float x, float y, float size, Paint.Align align, int color) {
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setColor(color);
        glow.setTextSize(size);
        glow.setTextAlign(align);
        glow.setColor((color & 0x00FFFFFF) | 0x77000000);
        c.drawText(s, x, y, glow);
        c.drawText(s, x, y, text);
    }

    private void txt(Canvas c, String s, float x, float y, float size, Paint.Align align, int color) {
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    private String fit(String s, float maxW, float size) {
        text.setTextSize(size);
        if (text.measureText(s) <= maxW) return s;
        while (s.length() > 1 && text.measureText(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private void drawWaiting(Canvas c, int w, int h) {
        long t = SystemClock.uptimeMillis();
        float cx = w / 2f, cy = h / 2f;
        // pulsierender Ring
        float r = u * 4 + (float) Math.sin(t / 300.0) * u * 0.4f;
        line.setStrokeWidth(u * 0.25f);
        line.setColor(G_DIM);
        c.drawCircle(cx, cy - u * 3, r, line);
        line.setColor(G);
        float sweep = (t / 4) % 360;
        c.drawArc(cx - r, cy - u * 3 - r, cx + r, cy - u * 3 + r, sweep, 70, false, line);
        String head = getContext().getString(connected ? R.string.loading : R.string.waiting);
        boolean blink = (t / 600) % 2 == 0;
        glowText(c, head + (blink ? "_" : " "), cx, cy + u * 3.5f, u * 1.5f, Paint.Align.CENTER, G);
        String hint = getContext().getString(R.string.waiting_hint);
        float hs = u * 0.8f;
        text.setTextSize(hs);
        while (hs > u * 0.4f && text.measureText(hint) > w - u * 2) { hs *= 0.95f; text.setTextSize(hs); }
        txt(c, hint, cx, cy + u * 5.6f, hs, Paint.Align.CENTER, G_DIM);
        postInvalidateDelayed(60);
    }

    private int[] tabNames() {
        return new int[]{R.string.tab_stat, R.string.tab_inv, R.string.tab_data, R.string.tab_map, R.string.tab_radio};
    }

    private void drawTabs(Canvas c, float l, float t, float r, float h) {
        int[] names = tabNames();
        float cw = (r - l) / names.length;
        float base = t + h - u * 0.2f;
        line.setColor(G);
        line.setStrokeWidth(u * 0.14f);
        float activeL = 0, activeR = 0;
        for (int i = 0; i < names.length; i++) {
            float cx = l + cw * (i + 0.5f);
            String s = getContext().getString(names[i]);
            text.setTextSize(u * 1.25f);
            float tw = text.measureText(s);
            if (i == tab) {
                glowText(c, s, cx, t + h * 0.68f, u * 1.25f, Paint.Align.CENTER, G);
                activeL = cx - tw / 2 - u * 0.6f;
                activeR = cx + tw / 2 + u * 0.6f;
            } else {
                txt(c, s, cx, t + h * 0.68f, u * 1.25f, Paint.Align.CENTER, G_DIM);
            }
            final int idx = i;
            hits.add(new Hit(new RectF(l + cw * i, t, l + cw * (i + 1), t + h + u), () -> { tab = idx; invalidate(); }));
        }
        // Pip-Boy-typische Linie mit "Klammer" um den aktiven Reiter
        c.drawLine(l, base, activeL, base, line);
        c.drawLine(activeR, base, r, base, line);
        c.drawLine(activeL, base, activeL, t + u * 0.2f, line);
        c.drawLine(activeR, base, activeR, t + u * 0.2f, line);
        c.drawLine(activeL, t + u * 0.2f, activeL + u * 0.6f, t + u * 0.2f, line);
        c.drawLine(activeR, t + u * 0.2f, activeR - u * 0.6f, t + u * 0.2f, line);
        c.drawLine(l, base, l, base + u * 0.6f, line);
        c.drawLine(r, base, r, base + u * 0.6f, line);
    }

    private int[] subTabs(int t) {
        switch (t) {
            case 0: return new int[]{R.string.sub_status, R.string.sub_special};
            case 1: return new int[]{R.string.sub_weapons, R.string.sub_apparel, R.string.sub_aid, R.string.sub_misc, R.string.sub_junk, R.string.sub_ammo};
            case 2: return new int[]{R.string.sub_quests, R.string.sub_world};
            case 3: return new int[]{location != null && location.optBoolean("interior") ? R.string.sub_localmap : R.string.sub_worldmap};
            default: return new int[]{R.string.sub_stations};
        }
    }

    private void drawSubTabs(Canvas c, float l, float t, float r, float h) {
        int[] names = subTabs(tab);
        if (sub[tab] >= names.length) sub[tab] = 0;
        float size = u * 0.95f;
        text.setTextSize(size);
        float gap = u * 1.6f;
        float total = 0;
        for (int n : names) total += text.measureText(getContext().getString(n)) + gap;
        total -= gap;
        float x = l + (r - l - total) / 2;
        for (int i = 0; i < names.length; i++) {
            String s = getContext().getString(names[i]);
            float tw = text.measureText(s);
            boolean active = i == sub[tab];
            if (active) glowText(c, s, x, t + h * 0.68f, size, Paint.Align.LEFT, G);
            else txt(c, s, x, t + h * 0.68f, size, Paint.Align.LEFT, G_DIM);
            final int idx = i;
            hits.add(new Hit(new RectF(x - gap / 2, t, x + tw + gap / 2, t + h), () -> { sub[tab] = idx; invalidate(); }));
            x += tw + gap;
        }
    }

    private void drawFooter(Canvas c, float l, float t, float r, float h) {
        fill.setColor(BG_HI);
        c.drawRect(l, t, r, t + h, fill);
        float y = t + h * 0.68f;
        float size = u * 0.95f;
        if (stats == null) return;
        String hp = getContext().getString(R.string.hp) + " " + (int) stats.optDouble("hp", 0) + "/" + (int) stats.optDouble("hpMax", 0);
        String ap = getContext().getString(R.string.ap) + " " + (int) stats.optDouble("ap", 0) + "/" + (int) stats.optDouble("apMax", 0);
        float w = r - l;
        txt(c, hp, l + u * 0.6f, y, size, Paint.Align.LEFT, G);
        txt(c, ap, r - u * 0.6f, y, size, Paint.Align.RIGHT, G);
        // Mitte: Stufe + XP-Balken
        String lvl = getContext().getString(R.string.level) + " " + stats.optInt("level");
        text.setTextSize(size);
        float lw = text.measureText(lvl);
        float barW = w * 0.22f;
        float cx = l + w / 2f;
        float sx = cx - (lw + u + barW) / 2;
        txt(c, lvl, sx, y, size, Paint.Align.LEFT, G);
        double xp = stats.optDouble("xpProgress", Double.NaN);
        float bx = sx + lw + u, by = t + h * 0.35f, bh = h * 0.32f;
        line.setStrokeWidth(u * 0.1f);
        line.setColor(G);
        c.drawRect(bx, by, bx + barW, by + bh, line);
        if (!Double.isNaN(xp)) {
            fill.setColor(G);
            c.drawRect(bx, by, bx + barW * (float) Math.max(0, Math.min(1, xp)), by + bh, fill);
        }
    }

    private void drawToast(Canvas c, int w, float bottom) {
        float size = u * 0.95f;
        text.setTextSize(size);
        float tw = text.measureText(toast) + u * 2;
        RectF r = new RectF((w - tw) / 2, bottom - u * 2.4f, (w + tw) / 2, bottom - u * 0.4f);
        fill.setColor(0xEE04140A);
        c.drawRect(r, fill);
        line.setColor(G);
        line.setStrokeWidth(u * 0.1f);
        c.drawRect(r, line);
        glowText(c, toast, w / 2f, r.centerY() + size * 0.35f, size, Paint.Align.CENTER, G);
    }

    // ---------------------------------------------------------------- STAT
    private void drawStat(Canvas c, RectF a) {
        if (sub[0] == 1) { drawSpecial(c, a); return; }
        // Figur in der Mitte, Gliedmaßen-Balken drumherum (eigene Zeichnung)
        float cx = a.centerX(), cy = a.top + a.height() * 0.47f;
        float s = a.height() / 15f;
        drawFigure(c, cx, cy, s);
        float bw = u * 4.5f;
        limbBar(c, "head", cx - bw / 2, cy - s * 7.2f, bw);
        limbBar(c, "leftArm", cx - s * 7.5f - bw, cy - s * 2.2f, bw);
        limbBar(c, "rightArm", cx + s * 7.5f, cy - s * 2.2f, bw);
        limbBar(c, "leftLeg", cx - s * 6f - bw, cy + s * 5.2f, bw);
        limbBar(c, "rightLeg", cx + s * 6f, cy + s * 5.2f, bw);
        // Kennzahlen unten
        if (stats != null) {
            float y = a.bottom - u * 0.6f;
            String rads = getContext().getString(R.string.rads) + " " + (int) stats.optDouble("rads", 0);
            String res = getContext().getString(R.string.dr) + " " + (int) stats.optDouble("damageResist", 0) + "   "
                    + getContext().getString(R.string.er) + " " + (int) stats.optDouble("energyResist", 0) + "   "
                    + getContext().getString(R.string.rr) + " " + (int) stats.optDouble("radResist", 0);
            txt(c, stats.optString("name"), a.left, a.top + u * 1.2f, u * 1.1f, Paint.Align.LEFT, G);
            txt(c, rads, a.right, a.top + u * 1.2f, u * 1.0f, Paint.Align.RIGHT, G);
            txt(c, res, a.centerX(), y, u * 0.9f, Paint.Align.CENTER, G_DIM);
        }
    }

    private float limbFrac(String key) {
        if (limbs == null) return 1f;
        JSONObject o = limbs.optJSONObject(key);
        if (o == null) return 1f;
        double max = o.optDouble("max", 100);
        if (max <= 0) max = 100;
        return (float) Math.max(0, Math.min(1, o.optDouble("value", max) / max));
    }

    private boolean crippled(String key) {
        if (limbs == null) return false;
        JSONObject o = limbs.optJSONObject(key);
        return o != null && o.optBoolean("crippled");
    }

    private int limbColor(String key) {
        if (!crippled(key)) return G;
        return (SystemClock.uptimeMillis() / 400) % 2 == 0 ? G : G_FAINT;
    }

    private void limbBar(Canvas c, String key, float x, float y, float w) {
        float h = u * 0.55f;
        line.setColor(G);
        line.setStrokeWidth(u * 0.08f);
        c.drawRect(x, y, x + w, y + h, line);
        fill.setColor(crippled(key) ? G_DIM : G);
        c.drawRect(x, y, x + w * limbFrac(key), y + h, fill);
        if (crippled(key)) postInvalidateDelayed(400);
    }

    /** Stilisierte Figur (Helm-Silhouette mit Overall) – eigenes Motiv. */
    private void drawFigure(Canvas c, float cx, float cy, float s) {
        line.setStrokeWidth(s * 0.32f);
        line.setStrokeCap(Paint.Cap.ROUND);
        // Kopf
        line.setColor(limbColor("head"));
        c.drawCircle(cx, cy - s * 4.6f, s * 1.5f, line);
        c.drawLine(cx - s * 0.6f, cy - s * 4.9f, cx - s * 0.2f, cy - s * 4.9f, line);
        c.drawLine(cx + s * 0.2f, cy - s * 4.9f, cx + s * 0.6f, cy - s * 4.9f, line);
        c.drawArc(cx - s * 0.7f, cy - s * 4.6f, cx + s * 0.7f, cy - s * 3.7f, 20, 140, false, line);
        // Torso
        line.setColor(limbColor("torso"));
        RectF torso = new RectF(cx - s * 1.9f, cy - s * 2.8f, cx + s * 1.9f, cy + s * 1.6f);
        c.drawRoundRect(torso, s * 0.8f, s * 0.8f, line);
        c.drawLine(cx, cy - s * 2.8f, cx, cy + s * 1.6f, line);
        // Torso-Balken im Körper
        float bw = s * 2.6f, bh = s * 0.45f, bx = cx - bw / 2, by = cy + s * 0.4f;
        fill.setColor(BG);
        c.drawRect(bx - s * 0.1f, by - s * 0.1f, bx + bw + s * 0.1f, by + bh + s * 0.1f, fill);
        fill.setColor(crippled("torso") ? G_DIM : G);
        c.drawRect(bx, by, bx + bw * limbFrac("torso"), by + bh, fill);
        // Arme
        line.setColor(limbColor("leftArm"));
        c.drawLine(cx - s * 2.1f, cy - s * 2.4f, cx - s * 3.6f, cy - s * 0.4f, line);
        c.drawLine(cx - s * 3.6f, cy - s * 0.4f, cx - s * 4.1f, cy + s * 1.6f, line);
        line.setColor(limbColor("rightArm"));
        c.drawLine(cx + s * 2.1f, cy - s * 2.4f, cx + s * 3.6f, cy - s * 0.4f, line);
        c.drawLine(cx + s * 3.6f, cy - s * 0.4f, cx + s * 4.1f, cy + s * 1.6f, line);
        // Beine
        line.setColor(limbColor("leftLeg"));
        c.drawLine(cx - s * 0.9f, cy + s * 1.8f, cx - s * 1.3f, cy + s * 4.4f, line);
        c.drawLine(cx - s * 1.3f, cy + s * 4.4f, cx - s * 1.5f, cy + s * 6.4f, line);
        c.drawLine(cx - s * 1.5f, cy + s * 6.4f, cx - s * 2.3f, cy + s * 6.4f, line);
        line.setColor(limbColor("rightLeg"));
        c.drawLine(cx + s * 0.9f, cy + s * 1.8f, cx + s * 1.3f, cy + s * 4.4f, line);
        c.drawLine(cx + s * 1.3f, cy + s * 4.4f, cx + s * 1.5f, cy + s * 6.4f, line);
        c.drawLine(cx + s * 1.5f, cy + s * 6.4f, cx + s * 2.3f, cy + s * 6.4f, line);
        line.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawSpecial(Canvas c, RectF a) {
        if (special == null) return;
        String[] keys = {"S", "P", "E", "C", "I", "A", "L"};
        int[] names = {R.string.sp_S, R.string.sp_P, R.string.sp_E, R.string.sp_C, R.string.sp_I, R.string.sp_A, R.string.sp_L};
        float rowH = Math.min(a.height() / 7.5f, u * 2.4f);
        float y = a.top + u * 0.4f;
        for (int i = 0; i < 7; i++) {
            JSONObject o = null;
            for (int j = 0; j < special.length(); j++) {
                JSONObject e = special.optJSONObject(j);
                if (e != null && keys[i].equals(e.optString("key"))) o = e;
            }
            int v = o != null ? (int) Math.round(o.optDouble("value", 0)) : 0;
            int b = o != null ? (int) Math.round(o.optDouble("base", 0)) : 0;
            float ty = y + rowH * 0.66f;
            glowText(c, keys[i], a.left + u * 0.4f, ty, u * 1.3f, Paint.Align.LEFT, G);
            txt(c, getContext().getString(names[i]), a.left + u * 2.4f, ty, u * 1.0f, Paint.Align.LEFT, G);
            // 10er-Kästchen
            float bx = a.left + a.width() * 0.48f, bw = a.width() * 0.36f, cell = bw / 10f;
            for (int k = 0; k < 10; k++) {
                RectF r = new RectF(bx + k * cell + cell * 0.12f, y + rowH * 0.25f, bx + (k + 1) * cell - cell * 0.12f, y + rowH * 0.75f);
                if (k < Math.min(v, 10)) { fill.setColor(G); c.drawRect(r, fill); }
                else { line.setColor(G_DIM); line.setStrokeWidth(u * 0.06f); c.drawRect(r, line); }
            }
            String val = v != b && b > 0 ? v + " (" + (v > b ? "+" : "") + (v - b) + ")" : String.valueOf(v);
            txt(c, val, a.right - u * 0.2f, ty, u * 1.1f, Paint.Align.RIGHT, G);
            line.setColor(G_FAINT);
            line.setStrokeWidth(1);
            c.drawLine(a.left, y + rowH, a.right, y + rowH, line);
            y += rowH;
        }
    }

    // ---------------------------------------------------------------- INV
    private boolean inSub(String type, int s) {
        switch (s) {
            case 0: return type.equals("weapon");
            case 1: return type.equals("apparel");
            case 2: return type.equals("aid");
            case 3: return type.equals("book") || type.equals("holotape") || type.equals("key") || type.equals("misc");
            case 4: return type.equals("junk");
            default: return type.equals("ammo");
        }
    }

    private void drawInv(Canvas c, RectF a) {
        List<JSONObject> items = new ArrayList<>();
        if (inventory != null) for (int i = 0; i < inventory.length(); i++) {
            JSONObject o = inventory.optJSONObject(i);
            if (o != null && inSub(o.optString("type"), sub[1])) items.add(o);
        }
        items.sort((x, y) -> {
            int e = Boolean.compare(y.optBoolean("equipped"), x.optBoolean("equipped"));
            return e != 0 ? e : x.optString("name").compareToIgnoreCase(y.optString("name"));
        });
        float rowH = u * 2.0f;
        float colVal = a.right - u * 0.4f, colWt = a.right - u * 5.2f;
        // Kopfzeile
        txt(c, getContext().getString(R.string.col_wt), colWt, a.top + u * 0.9f, u * 0.75f, Paint.Align.RIGHT, G_DIM);
        txt(c, getContext().getString(R.string.col_val), colVal, a.top + u * 0.9f, u * 0.75f, Paint.Align.RIGHT, G_DIM);
        RectF list = new RectF(a.left, a.top + u * 1.4f, a.right, a.bottom);
        if (items.isEmpty()) {
            txt(c, getContext().getString(R.string.empty), a.centerX(), list.top + u * 3, u * 1.0f, Paint.Align.CENTER, G_DIM);
            return;
        }
        String key = "inv" + sub[1];
        float off = beginScroll(key, items.size() * rowH, list.height());
        c.save();
        c.clipRect(list);
        float y = list.top - off;
        boolean actionable = sub[1] <= 2;
        for (JSONObject o : items) {
            if (y + rowH >= list.top && y <= list.bottom) {
                boolean eq = o.optBoolean("equipped");
                RectF row = new RectF(a.left, y, a.right, y + rowH);
                if (eq) { fill.setColor(BG_HI); c.drawRect(row, fill); }
                float ty = y + rowH * 0.66f;
                if (eq) { fill.setColor(G); c.drawRect(a.left + u * 0.3f, y + rowH * 0.36f, a.left + u * 0.9f, y + rowH * 0.64f, fill); }
                int count = o.optInt("count", 1);
                String name = o.optString("name") + (count > 1 ? " (" + count + ")" : "");
                name = fit(name, colWt - u * 4.5f - (a.left + u * 1.4f), u * 1.0f);
                if (eq) glowText(c, name, a.left + u * 1.4f, ty, u * 1.0f, Paint.Align.LEFT, G);
                else txt(c, name, a.left + u * 1.4f, ty, u * 1.0f, Paint.Align.LEFT, G);
                txt(c, fmtWeight(o.optDouble("weight", 0)), colWt, ty, u * 0.9f, Paint.Align.RIGHT, G_DIM);
                txt(c, String.valueOf(o.optInt("value")), colVal, ty, u * 0.9f, Paint.Align.RIGHT, G_DIM);
                line.setColor(G_FAINT);
                line.setStrokeWidth(1);
                c.drawLine(a.left, y + rowH, a.right, y + rowH, line);
                final JSONObject item = o;
                RectF hit = new RectF(row);
                hit.intersect(list);
                hits.add(new Hit(hit, () -> onItemTap(item, actionable)));
            }
            y += rowH;
        }
        c.restore();
        drawScrollbar(c, list, items.size() * rowH, off);
    }

    private static String fmtWeight(double w) {
        if (w <= 0) return "–";
        if (w >= 10) return String.valueOf(Math.round(w));
        return String.format(Locale.ROOT, "%.1f", w);
    }

    private void onItemTap(JSONObject item, boolean actionable) {
        String name = item.optString("name");
        if (!actionable) { showToast(getContext().getString(R.string.no_action, name)); invalidate(); return; }
        try {
            JSONObject p = new JSONObject();
            p.put("id", item.optString("id"));
            p.put("stack", item.optInt("stack"));
            String id = client.command("toggle", p);
            pending.put(id, name);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- DATA
    /** Quests aus dem internen Pip-Boy-Modell (aktiv zuerst); Fallback: direkte Questziele. */
    private LinkedHashMap<String, List<JSONObject>> questGroups(List<String> activeOut) {
        LinkedHashMap<String, List<JSONObject>> byQuest = new LinkedHashMap<>();
        if (pipQuests != null && pipQuests.length() > 0) {
            List<JSONObject> qs = new ArrayList<>();
            for (int i = 0; i < pipQuests.length(); i++) { JSONObject q = pipQuests.optJSONObject(i); if (q != null) qs.add(q); }
            qs.sort((x, y) -> {
                int e = Boolean.compare(y.optBoolean("active"), x.optBoolean("active"));
                if (e != 0) return e;
                return Boolean.compare(y.optBoolean("enabled"), x.optBoolean("enabled"));
            });
            for (JSONObject q : qs) {
                String name = q.optString("text");
                List<JSONObject> objs = new ArrayList<>();
                JSONArray arr = q.optJSONArray("objectives");
                if (arr != null) for (int j = 0; j < arr.length(); j++) {
                    JSONObject o = arr.optJSONObject(j);
                    if (o == null || o.optString("text").trim().isEmpty()) continue;
                    JSONObject n = new JSONObject();
                    try {
                        n.put("objective", o.optString("text"));
                        n.put("state", o.optBoolean("failed") ? "failed" : o.optBoolean("completed") ? "completed" : "active");
                    } catch (Exception ignored) { }
                    objs.add(n);
                }
                // offene Ziele vor erledigten
                objs.sort((x, y) -> Boolean.compare(!x.optString("state").equals("active"), !y.optString("state").equals("active")));
                byQuest.put(name, objs);
                if (q.optBoolean("active")) activeOut.add(name);
            }
            return byQuest;
        }
        if (quests != null) for (int i = 0; i < quests.length(); i++) {
            JSONObject o = quests.optJSONObject(i);
            if (o == null || o.optString("objective").trim().isEmpty()) continue;
            String q = o.optString("quest");
            if (!byQuest.containsKey(q)) byQuest.put(q, new ArrayList<>());
            byQuest.get(q).add(o);
            if (o.optString("state").equals("active") && !activeOut.contains(q)) activeOut.add(q);
        }
        return byQuest;
    }

    private void drawData(Canvas c, RectF a) {
        if (sub[2] == 1) { drawWorld(c, a); return; }
        List<String> activeQuests = new ArrayList<>();
        LinkedHashMap<String, List<JSONObject>> byQuest = questGroups(activeQuests);
        if (byQuest.isEmpty()) {
            txt(c, getContext().getString(R.string.no_quests), a.centerX(), a.top + u * 3, u * 1.0f, Paint.Align.CENTER, G_DIM);
            return;
        }
        float qH = u * 2.0f, oH = u * 1.6f;
        float total = 0;
        for (List<JSONObject> l : byQuest.values()) total += qH + l.size() * oH + u * 0.5f;
        float off = beginScroll("quests", total, a.height());
        float y = a.top - off;
        for (Map.Entry<String, List<JSONObject>> e : byQuest.entrySet()) {
            boolean anyActive = activeQuests.contains(e.getKey());
            if (anyActive) { fill.setColor(G); c.drawRect(a.left, y + qH * 0.3f, a.left + u * 0.5f, y + qH * 0.7f, fill); }
            glowText(c, fit(e.getKey().isEmpty() ? "?" : e.getKey(), a.width() - u * 2, u * 1.1f), a.left + u * 1.0f, y + qH * 0.68f, u * 1.1f, Paint.Align.LEFT, anyActive ? G : G_DIM);
            y += qH;
            for (JSONObject o : e.getValue()) {
                String st = o.optString("state");
                RectF box = new RectF(a.left + u * 1.6f, y + oH * 0.25f, a.left + u * 2.3f, y + oH * 0.75f);
                line.setColor(st.equals("active") ? G : G_DIM);
                line.setStrokeWidth(u * 0.08f);
                c.drawRect(box, line);
                if (st.equals("completed")) { fill.setColor(G_DIM); c.drawRect(box.left + u * 0.15f, box.top + u * 0.15f, box.right - u * 0.15f, box.bottom - u * 0.15f, fill); }
                if (st.equals("failed")) { c.drawLine(box.left, box.top, box.right, box.bottom, line); c.drawLine(box.left, box.bottom, box.right, box.top, line); }
                txt(c, fit(o.optString("objective"), a.width() - u * 3.6f, u * 0.9f), a.left + u * 2.9f, y + oH * 0.68f, u * 0.9f, Paint.Align.LEFT, st.equals("active") ? G : G_DIM);
                y += oH;
            }
            y += u * 0.5f;
        }
        drawScrollbar(c, a, total, off);
    }

    private String dateString() {
        if (time == null) return "–";
        int day = (int) time.optDouble("day", 0);
        int month = (int) time.optDouble("month", 0);         // am Gerät geprüft: 1-basiert (23.10. = Spielstart)
        int year = (int) time.optDouble("year", 0);
        if (year < 1000) year += 2000;                        // Spiel speichert 287 für 2287
        double hr = time.optDouble("hour", 0);
        int hh = (int) Math.floor(hr), mm = (int) Math.floor((hr - hh) * 60);
        return String.format(Locale.ROOT, "%02d.%02d.%d  %02d:%02d", day, month, year, hh, mm);
    }

    private void drawWorld(Canvas c, RectF a) {
        float y = a.top + u * 1.6f;
        float lx = a.left + u * 0.4f, vx = a.left + a.width() * 0.34f;
        String[][] rows = {
                {getContext().getString(R.string.date), dateString()},
                {getContext().getString(R.string.location), location != null ? location.optString("location") : ""},
                {getContext().getString(R.string.cell), location != null ? location.optString("cell") + (location.optBoolean("interior") ? "  [" + getContext().getString(R.string.interior) + "]" : "") : ""},
                {getContext().getString(R.string.worldspace), location != null ? location.optString("worldspace") : ""},
                {getContext().getString(R.string.position), location != null ? String.format(Locale.ROOT, "%.0f / %.0f / %.0f", location.optDouble("x"), location.optDouble("y"), location.optDouble("z")) : ""},
                {getContext().getString(R.string.caps), stats != null ? String.valueOf((long) stats.optDouble("caps", 0)) : ""},
                {getContext().getString(R.string.weight), stats != null ? Math.round(stats.optDouble("weight", 0)) + " / " + Math.round(stats.optDouble("weightMax", 0)) : ""},
        };
        for (String[] r : rows) {
            txt(c, r[0], lx, y, u * 0.9f, Paint.Align.LEFT, G_DIM);
            glowText(c, fit(r[1].isEmpty() ? "–" : r[1], a.right - vx, u * 1.05f), vx, y, u * 1.05f, Paint.Align.LEFT, G);
            line.setColor(G_FAINT);
            line.setStrokeWidth(1);
            c.drawLine(a.left, y + u * 0.7f, a.right, y + u * 0.7f, line);
            y += u * 2.2f;
        }
    }

    // ---------------------------------------------------------------- MAP
    private static double num(JSONObject o, String... keys) {
        if (o == null) return Double.NaN;
        for (String k : keys) if (o.has(k)) return o.optDouble(k, Double.NaN);
        return Double.NaN;
    }

    private void drawMap(Canvas c, RectF a) {
        // Raster
        line.setColor(G_FAINT);
        line.setStrokeWidth(1);
        float step = u * 2.5f;
        for (float x = a.left; x <= a.right; x += step) c.drawLine(x, a.top, x, a.bottom, line);
        for (float y = a.top; y <= a.bottom; y += step) c.drawLine(a.left, y, a.right, y, line);
        line.setColor(G_DIM);
        c.drawRect(a, line);

        boolean interior = location != null && location.optBoolean("interior");
        JSONObject world = map != null ? map.optJSONObject(interior ? "Local" : "World") : null;
        JSONArray questMarks = world != null ? world.optJSONArray("Quests") : null;
        JSONArray doors = interior && world != null ? world.optJSONArray("Doors") : null;
        JSONArray locs = world != null ? world.optJSONArray("Locations") : null;
        JSONObject player = world != null ? world.optJSONObject("Player") : null;
        double px = num(player, "X", "x"), py = num(player, "Y", "y");
        double rot = num(player, "Rotation", "rotation");
        if (Double.isNaN(px) && location != null && !interior) {
            px = location.optDouble("x"); py = location.optDouble("y");
            rot = Math.toDegrees(location.optDouble("heading", 0));
        }

        // Ausdehnung: Extents (NW/NE/SW) oder aus den Markern
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        JSONObject ext = world != null && !interior ? world.optJSONObject("Extents") : null;
        if (ext != null) {
            for (Iterator<String> it = ext.keys(); it.hasNext(); ) {
                String k = it.next();
                double v = ext.optDouble(k, Double.NaN);
                if (Double.isNaN(v)) continue;
                if (k.endsWith("X")) { minX = Math.min(minX, v); maxX = Math.max(maxX, v); }
                if (k.endsWith("Y")) { minY = Math.min(minY, v); maxY = Math.max(maxY, v); }
            }
        }
        if (minX == Double.MAX_VALUE) {
            // Innenräume: Ausdehnung aus Türen, Questmarkern und Spieler (+ Rand)
            List<JSONObject> pts = new ArrayList<>();
            for (JSONArray arr : new JSONArray[]{locs, doors, questMarks})
                if (arr != null) for (int i = 0; i < arr.length(); i++) if (arr.optJSONObject(i) != null) pts.add(arr.optJSONObject(i));
            if (player != null) pts.add(player);
            for (JSONObject o : pts) {
                double x = num(o, "X", "x"), y = num(o, "Y", "y");
                if (Double.isNaN(x) || Double.isNaN(y)) continue;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            if (minX <= maxX) { double m = Math.max(1500, Math.max(maxX - minX, maxY - minY) * 0.15); minX -= m; maxX += m; minY -= m; maxY += m; }
        }
        boolean haveExtent = minX < maxX && minY < maxY;
        if (!haveExtent && !Double.isNaN(px)) { minX = px - 20000; maxX = px + 20000; minY = py - 20000; maxY = py + 20000; haveExtent = true; }

        if (interior) {
            txt(c, getContext().getString(R.string.map_interior, location.optString("cell")), a.left + u * 0.5f, a.top + u * 1.3f, u * 0.9f, Paint.Align.LEFT, G);
        }
        if (!haveExtent) {
            txt(c, getContext().getString(R.string.map_nodata), a.centerX(), a.centerY(), u * 1.0f, Paint.Align.CENTER, G_DIM);
            return;
        }
        // quadratisch einpassen, y nach oben
        double spanX = maxX - minX, spanY = maxY - minY;
        double scale = Math.min(a.width() / spanX, a.height() / spanY) * 0.96;
        double cxw = (minX + maxX) / 2, cyw = (minY + maxY) / 2;
        if (mapZoom && !Double.isNaN(px)) { scale *= 3.5; cxw = px; cyw = py; }
        final double fs = scale, fcx = cxw, fcy = cyw;
        float ox = a.centerX(), oy = a.centerY();
        java.util.function.DoubleUnaryOperator sx = v -> ox + (v - fcx) * fs;
        java.util.function.DoubleUnaryOperator sy = v -> oy - (v - fcy) * fs;

        if (locs != null) {
            float ms = u * 0.35f;
            for (int i = 0; i < locs.length(); i++) {
                JSONObject o = locs.optJSONObject(i);
                if (o == null) continue;
                if (o.has("Visible") && !o.optBoolean("Visible")) continue;
                double x = num(o, "X", "x"), y = num(o, "Y", "y");
                if (Double.isNaN(x)) continue;
                float mx = (float) sx.applyAsDouble(x), my = (float) sy.applyAsDouble(y);
                if (!a.contains(mx, my)) continue;
                boolean disc = !o.has("Discovered") || o.optBoolean("Discovered");
                Path d = new Path();
                d.moveTo(mx, my - ms); d.lineTo(mx + ms, my); d.lineTo(mx, my + ms); d.lineTo(mx - ms, my); d.close();
                if (disc) { fill.setColor(G); c.drawPath(d, fill); }
                else { line.setColor(G_DIM); line.setStrokeWidth(u * 0.06f); c.drawPath(d, line); }
                if (mapZoom && disc) txt(c, o.optString("text", o.optString("Name")), mx + ms * 1.6f, my + ms, u * 0.65f, Paint.Align.LEFT, G_DIM);
            }
        }
        // Türen (lokal) als offene Kreise, Questmarker als Quadrate mit Glühen
        if (doors != null) for (int i = 0; i < doors.length(); i++) {
            JSONObject o = doors.optJSONObject(i);
            double x = num(o, "X", "x"), y = num(o, "Y", "y");
            if (Double.isNaN(x)) continue;
            float mx = (float) sx.applyAsDouble(x), my = (float) sy.applyAsDouble(y);
            line.setColor(G); line.setStrokeWidth(u * 0.1f);
            c.drawCircle(mx, my, u * 0.4f, line);
            txt(c, o.optString("Name"), mx + u * 0.6f, my + u * 0.3f, u * 0.65f, Paint.Align.LEFT, G_DIM);
        }
        if (questMarks != null) for (int i = 0; i < questMarks.length(); i++) {
            JSONObject o = questMarks.optJSONObject(i);
            double x = num(o, "X", "x"), y = num(o, "Y", "y");
            if (Double.isNaN(x)) continue;
            float mx = (float) sx.applyAsDouble(x), my = (float) sy.applyAsDouble(y);
            float q = u * 0.45f;
            glow.setColor(0x991AFF80);
            c.drawRect(mx - q, my - q, mx + q, my + q, glow);
            line.setColor(G); line.setStrokeWidth(u * 0.12f);
            c.drawRect(mx - q, my - q, mx + q, my + q, line);
            fill.setColor(G);
            c.drawRect(mx - q * 0.4f, my - q * 0.4f, mx + q * 0.4f, my + q * 0.4f, fill);
        }
        if (!Double.isNaN(px)) {
            float mx = (float) sx.applyAsDouble(px), my = (float) sy.applyAsDouble(py);
            float s = u * 0.9f;
            c.save();
            c.rotate((float) (Double.isNaN(rot) ? 0 : rot), mx, my);
            Path p = new Path();
            p.moveTo(mx, my - s); p.lineTo(mx + s * 0.65f, my + s * 0.75f); p.lineTo(mx, my + s * 0.35f); p.lineTo(mx - s * 0.65f, my + s * 0.75f); p.close();
            glow.setColor(0x991AFF80);
            c.drawPath(p, glow);
            fill.setColor(G);
            c.drawPath(p, fill);
            c.restore();
        }
        if (location != null) {
            String l = location.optString("location");
            if (l.isEmpty()) l = location.optString("worldspace");
            glowText(c, fit(l, a.width() * 0.6f, u * 0.95f), a.right - u * 0.5f, a.bottom - u * 0.6f, u * 0.95f, Paint.Align.RIGHT, G);
        }
        txt(c, getContext().getString(R.string.map_zoom), a.left + u * 0.5f, a.bottom - u * 0.6f, u * 0.7f, Paint.Align.LEFT, G_DIM);
        hits.add(new Hit(new RectF(a), () -> { mapZoom = !mapZoom; invalidate(); }));
    }

    // ---------------------------------------------------------------- RADIO
    private void drawRadio(Canvas c, RectF a) {
        boolean on = radio != null && radio.optBoolean("enabled");
        // Schalter
        String sw = getContext().getString(on ? R.string.radio_on : R.string.radio_off);
        RectF btn = new RectF(a.left, a.top, a.left + a.width() * 0.42f, a.top + u * 2.2f);
        if (on) { fill.setColor(G); c.drawRect(btn, fill); txt(c, sw, btn.centerX(), btn.centerY() + u * 0.35f, u * 1.0f, Paint.Align.CENTER, BG); }
        else { line.setColor(G); line.setStrokeWidth(u * 0.1f); c.drawRect(btn, line); txt(c, sw, btn.centerX(), btn.centerY() + u * 0.35f, u * 1.0f, Paint.Align.CENTER, G); }
        hits.add(new Hit(btn, () -> {
            JSONObject p = new JSONObject();
            try { p.put("enable", !(radio != null && radio.optBoolean("enabled"))); } catch (Exception ignored) { }
            client.command("radio", p);
        }));
        // Senderliste links
        RectF list = new RectF(a.left, a.top + u * 3.0f, a.left + a.width() * 0.5f, a.bottom);
        JSONArray st = radio != null ? radio.optJSONArray("stations") : null;
        List<JSONObject> stations = new ArrayList<>();
        if (st != null) for (int i = 0; i < st.length(); i++) {
            JSONObject o = st.optJSONObject(i);
            if (o == null) continue;
            if (o.has("inRange") && !o.optBoolean("inRange")) continue;
            stations.add(o);
        }
        if (stations.isEmpty()) {
            txt(c, getContext().getString(R.string.radio_none), list.left, list.top + u * 1.2f, u * 0.9f, Paint.Align.LEFT, G_DIM);
        } else {
            float rowH = u * 1.9f;
            float off = beginScroll("radio", stations.size() * rowH, list.height());
            float y = list.top - off;
            for (JSONObject o : stations) {
                double rf = radio != null ? radio.optDouble("frequency", 0) : 0;
                boolean active = o.optBoolean("active") || (on && Math.abs(o.optDouble("frequency", -1) - rf) < 0.15);
                if (active) { fill.setColor(BG_HI); c.drawRect(list.left, y, list.right, y + rowH, fill); fill.setColor(G); c.drawRect(list.left + u * 0.2f, y + rowH * 0.36f, list.left + u * 0.8f, y + rowH * 0.64f, fill); }
                String name = o.optString("text", o.optString("name", "?"));
                if (active) glowText(c, fit(name, list.width() - u * 1.6f, u * 0.95f), list.left + u * 1.3f, y + rowH * 0.66f, u * 0.95f, Paint.Align.LEFT, G);
                else txt(c, fit(name, list.width() - u * 1.6f, u * 0.95f), list.left + u * 1.3f, y + rowH * 0.66f, u * 0.95f, Paint.Align.LEFT, G);
                y += rowH;
            }
        }
        // Wellenform rechts
        RectF wave = new RectF(a.left + a.width() * 0.56f, a.top + u * 3.0f, a.right, a.bottom - u * 1.5f);
        line.setColor(G_DIM);
        line.setStrokeWidth(1);
        c.drawRect(wave, line);
        for (int i = 1; i < 8; i++) {
            float x = wave.left + wave.width() * i / 8f;
            c.drawLine(x, wave.bottom - u * 0.4f, x, wave.bottom, line);
        }
        line.setColor(G);
        line.setStrokeWidth(u * 0.14f);
        Path p = new Path();
        double t = SystemClock.uptimeMillis() / 1000.0;
        int n = 120;
        for (int i = 0; i <= n; i++) {
            float x = wave.left + wave.width() * i / n;
            double ph = i / (double) n * Math.PI * 6;
            double amp = on ? (0.32 * Math.sin(ph + t * 5) + 0.12 * Math.sin(ph * 2.7 - t * 9) + 0.05 * Math.sin(ph * 7 + t * 13)) : 0;
            float y = (float) (wave.centerY() - amp * wave.height());
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        c.drawPath(p, line);
        double f = radio != null ? radio.optDouble("frequency", 0) : 0;
        if (on && f > 0) txt(c, String.format(Locale.ROOT, "%.1f", f), wave.right, a.bottom - u * 0.3f, u * 0.9f, Paint.Align.RIGHT, G_DIM);
    }

    // =====================================================================
    // Scrollen + Touch
    // =====================================================================
    private float beginScroll(String key, float total, float visible) {
        scrollKey = key;
        contentMaxScroll = Math.max(0, total - visible);
        float v = scroll.containsKey(key) ? scroll.get(key) : 0f;
        v = Math.max(0, Math.min(contentMaxScroll, v));
        scroll.put(key, v);
        return v;
    }

    private void drawScrollbar(Canvas c, RectF list, float total, float off) {
        if (total <= list.height()) return;
        float frac = list.height() / total;
        float h = Math.max(u, list.height() * frac);
        float y = list.top + (list.height() - h) * (off / (total - list.height()));
        fill.setColor(G_DIM);
        c.drawRect(list.right - u * 0.25f, y, list.right, y + h, fill);
    }

    private float downX, downY, lastY;
    private boolean dragging;
    private String dragKey;

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = lastY = e.getY();
                dragging = false;
                dragKey = contentRect.contains(downX, downY) ? scrollKey : null;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getY() - downY) > u * 0.8f) dragging = true;
                if (dragging && dragKey != null && scroll.containsKey(dragKey)) {
                    float v = scroll.get(dragKey) - (e.getY() - lastY);
                    scroll.put(dragKey, Math.max(0, Math.min(contentMaxScroll, v)));
                    invalidate();
                }
                lastY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    for (int i = hits.size() - 1; i >= 0; i--) {
                        Hit h = hits.get(i);
                        if (h.r.contains(e.getX(), e.getY())) {
                            performClick();
                            h.action.run();
                            break;
                        }
                    }
                }
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }
}
