package com.bhaskar.tvremote;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xiaomi's own remote services, used when a Mi TV does not offer the Android TV remote service.
 *  - "MiLink": plain TCP on port 6091 (newer Mi TVs / Mi Box, no pairing code).
 *  - "Old HTTP": port 6095 (older Mi TVs).
 * Keys use the normal Android key codes from {@link Keys}.
 */
final class MiLink {
    static final int PORT = 6091;
    static final int HTTP_PORT = 6095;

    interface Closed { void onClosed(String why); }

    private final Socket sock;
    private final DataOutputStream out;
    private final AtomicInteger ids = new AtomicInteger(1);
    private final Closed closed;
    private volatile boolean dead;

    private MiLink(Socket s, Closed c) throws IOException {
        sock = s;
        out = new DataOutputStream(s.getOutputStream());
        closed = c;
    }

    /** Opens the connection and starts the heartbeat/reader threads. */
    static MiLink open(String host, int timeoutMs, Closed cb) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, PORT), timeoutMs);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        final MiLink m = new MiLink(s, cb);
        Trace.add("Xiaomi link open on port " + PORT);
        new Thread(m::readLoop, "milink-read").start();
        new Thread(m::beatLoop, "milink-beat").start();
        return m;
    }

    private void readLoop() {
        try {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            while (!dead) {
                int type = in.readUnsignedByte();
                int len = in.readUnsignedShort();
                byte[] payload = new byte[len];
                in.readFully(payload);
                if (type == 4 && len > 0 && payload[0] == 2) Trace.add("TV acknowledged a key");
            }
        } catch (Exception e) {
            fail(TlsUtil.describe(e));
        }
    }

    private void beatLoop() {
        try {
            while (!dead) {
                Thread.sleep(5000);
                synchronized (out) {
                    out.write(new byte[]{2, 0, 0});    // heartbeat: type 2, empty payload
                    out.flush();
                }
            }
        } catch (Exception e) {
            fail(TlsUtil.describe(e));
        }
    }

    private void fail(String why) {
        if (dead) return;
        dead = true;
        try { sock.close(); } catch (Exception ignored) { }
        Trace.add("Xiaomi link closed: " + why);
        if (closed != null) closed.onClosed(why);
    }

    void close() {
        dead = true;
        try { sock.close(); } catch (Exception ignored) { }
    }

    /** Presses and releases a key. */
    void key(int code) {
        try {
            sendKey(code, 0);
            sendKey(code, 1);
        } catch (IOException e) {
            fail(TlsUtil.describe(e));
        }
    }

    private void sendKey(int code, int action) throws IOException {
        ByteArrayOutputStream tl = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(tl);
        d.writeByte(1);  d.writeInt(0);        // metaState
        d.writeByte(2);  d.writeInt(action);   // 0 = down, 1 = up
        d.writeByte(3);  d.writeInt(code);     // key code
        d.writeByte(4);  d.writeInt(0);        // scanCode
        d.writeByte(5);  d.writeInt(0);        // repeatCount
        d.writeByte(6);  d.writeInt(0);        // flags
        d.writeByte(7);  d.writeLong(0);       // downTime (must be 0)
        d.writeByte(8);  d.writeLong(0);       // eventTime (must be 0)
        d.writeByte(10); d.writeInt(-1);       // deviceId: virtual keyboard
        d.writeByte(11); d.writeInt(0x101);    // source: keyboard
        byte[] data = tl.toByteArray();

        ByteArrayOutputStream fr = new ByteArrayOutputStream();
        DataOutputStream f = new DataOutputStream(fr);
        f.writeByte(4);                         // frame type: key
        f.writeShort(1 + 4 + 2 + data.length);  // payload length
        f.writeByte(1);                         // 1 = send
        f.writeInt(ids.getAndIncrement());
        f.writeShort(data.length);
        f.write(data);
        synchronized (out) {
            out.write(fr.toByteArray());
            out.flush();
        }
    }

    // ------------------------------------------------------------------ old HTTP remote (port 6095)

    /** Name the old HTTP remote understands for a key code, or null. */
    static String httpName(int code) {
        switch (code) {
            case Keys.UP: return "up";
            case Keys.DOWN: return "down";
            case Keys.LEFT: return "left";
            case Keys.RIGHT: return "right";
            case Keys.CENTER: case Keys.ENTER: return "enter";
            case Keys.HOME: return "home";
            case Keys.BACK: return "back";
            case Keys.MENU: return "menu";
            case Keys.VOL_UP: return "volumeup";
            case Keys.VOL_DOWN: return "volumedown";
            case Keys.POWER: return "power";
            default: return null;
        }
    }

    private static boolean httpGet(String url, int timeoutMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            int rc = c.getResponseCode();
            try { c.getInputStream().close(); } catch (Exception ignored) { }
            c.disconnect();
            return rc == 200;
        } catch (Exception e) {
            Trace.add("http " + url + ": " + TlsUtil.describe(e));
            return false;
        }
    }

    static boolean httpAlive(String host) {
        return httpGet("http://" + host + ":" + HTTP_PORT + "/request?action=isalive", 2500);
    }

    static boolean httpKey(String host, int code) {
        String n = httpName(code);
        if (n == null) return false;
        return httpGet("http://" + host + ":" + HTTP_PORT + "/controller?action=keyevent&keycode=" + n, 2500);
    }

    /** Quick check whether a TCP port answers. */
    static boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
