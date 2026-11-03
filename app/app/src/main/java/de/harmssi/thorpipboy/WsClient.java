package de.harmssi.thorpipboy;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimaler WebSocket-Client (RFC 6455) mit automatischem Wiederverbinden. */
public class WsClient {
    public interface Listener {
        void onConnection(boolean connected);
        void onMessage(JSONObject message);
    }

    private static final String TAG = "ThorPipBoy";
    private final String host;
    private final int port;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean running;
    private volatile Socket socket;
    private volatile OutputStream out;
    private Thread thread;
    private int cmdSeq;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    public WsClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "pipboy-ws");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly();
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                connectAndRead();
            } catch (Exception e) {
                Log.d(TAG, "Verbindung: " + e.getMessage());
            }
            closeQuietly();
            main.post(() -> listener.onConnection(false));
            if (!running) break;
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        }
    }

    private void connectAndRead() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 1500);
        s.setTcpNoDelay(true);
        s.setSoTimeout(10000);   // Server sendet mindestens Heartbeat-Antworten
        socket = s;
        InputStream in = s.getInputStream();
        OutputStream o = s.getOutputStream();
        byte[] keyBytes = new byte[16];
        rnd.nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        String req = "GET / HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        o.write(req.getBytes(StandardCharsets.US_ASCII));
        o.flush();
        // Antwort-Header lesen
        StringBuilder hdr = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            hdr.append((char) c);
            if (hdr.length() > 4 && hdr.substring(hdr.length() - 4).equals("\r\n\r\n")) break;
            if (hdr.length() > 8192) throw new IOException("Header zu lang");
        }
        if (!hdr.toString().startsWith("HTTP/1.1 101")) throw new IOException("kein WebSocket");
        out = o;
        main.post(() -> listener.onConnection(true));
        Thread hb = new Thread(() -> {
            while (running && socket == s) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                send("{\"type\":\"heartbeat\"}");
            }
        }, "pipboy-hb");
        hb.setDaemon(true);
        hb.start();

        DataInputStream din = new DataInputStream(in);
        ByteArrayOutputStream frag = new ByteArrayOutputStream();
        while (running) {
            int b0 = din.readUnsignedByte();
            int b1 = din.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = din.readUnsignedShort();
            else if (len == 127) len = din.readLong();
            if (len > 64L * 1024 * 1024) throw new IOException("Frame zu groß");
            byte[] mask = null;
            if ((b1 & 0x80) != 0) { mask = new byte[4]; din.readFully(mask); }
            byte[] payload = new byte[(int) len];
            din.readFully(payload);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            if (op == 0x8) throw new IOException("Server hat geschlossen");
            if (op == 0x9) { final byte[] p = payload; writer.execute(() -> sendFrame(0xA, p)); continue; }
            if (op == 0x0 || op == 0x1 || op == 0x2) {
                frag.write(payload);
                if (fin) {
                    String text = new String(frag.toByteArray(), StandardCharsets.UTF_8);
                    frag.reset();
                    try {
                        JSONObject m = new JSONObject(text);
                        main.post(() -> listener.onMessage(m));
                    } catch (Exception e) {
                        Log.w(TAG, "JSON: " + e.getMessage());
                    }
                }
            }
        }
    }

    private void closeQuietly() {
        Socket s = socket;
        socket = null;
        out = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    /** Asynchron senden (nie auf dem UI-Thread ins Netz schreiben). */
    public void send(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writer.execute(() -> sendFrame(0x1, data));
    }

    private synchronized boolean sendFrame(int op, byte[] data) {
        OutputStream o = out;
        if (o == null) return false;
        try {
            ByteArrayOutputStream f = new ByteArrayOutputStream(data.length + 14);
            f.write(0x80 | op);
            if (data.length < 126) f.write(0x80 | data.length);
            else if (data.length < 65536) { f.write(0x80 | 126); f.write(data.length >> 8); f.write(data.length & 0xFF); }
            else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) (((long) data.length >> (8 * i)) & 0xFF)); }
            byte[] mask = new byte[4];
            rnd.nextBytes(mask);
            f.write(mask);
            for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i & 3]);
            o.write(f.toByteArray());
            o.flush();
            return true;
        } catch (IOException e) {
            closeQuietly();
            return false;
        }
    }

    public void subscribe(String id, int frequencyMs, String... keys) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "subscribe");
            m.put("id", id);
            JSONObject st = new JSONObject();
            st.put("frequency", frequencyMs);
            st.put("sendOnChange", true);
            m.put("settings", st);
            JSONArray f = new JSONArray();
            for (String k : keys) f.put(k);
            m.put("fields", f);
            send(m.toString());
        } catch (Exception ignored) { }
    }

    /** Sendet einen Befehl; liefert die Befehls-ID (für commandResult). */
    public String command(String command, JSONObject params) {
        String id = "c" + (++cmdSeq);
        try {
            JSONObject m = new JSONObject();
            m.put("type", "command");
            m.put("id", id);
            m.put("command", command);
            m.put("params", params != null ? params : new JSONObject());
            send(m.toString());
        } catch (Exception ignored) { }
        return id;
    }
}
