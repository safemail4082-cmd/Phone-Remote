package com.bhaskar.tvremote;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;

/** Live remote-control session with a TV (Android TV Remote protocol v2, port 6466). */
final class TvClient {
    static final int IDLE = 0, CONNECTING = 1, READY = 2, FAILED = 3;

    interface Listener {
        /** msg is "pair" when the TV does not know this phone yet, "net" when it is unreachable */
        void onState(int state, String msg);

        void onVolume(int level, int max, boolean muted);
    }

    private final Context ctx;
    private final Listener ls;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object wlock = new Object();
    private volatile SSLSocket sock;
    private volatile OutputStream out;
    private volatile int gen;
    private volatile int state = IDLE;
    private volatile String lastError = "";

    TvClient(Context c, Listener l) {
        ctx = c.getApplicationContext();
        ls = l;
    }

    int state() { return state; }

    String lastError() { return lastError; }

    boolean ready() { return state == READY; }

    private void setState(int s, String m) {
        state = s;
        ls.onState(s, m);
    }

    void connect(final String host) {
        final int my = ++gen;
        closeSocket();
        setState(CONNECTING, "");
        new Thread(() -> run(host, my, null), "tv-session").start();
    }

    void disconnect() {
        gen++;
        closeSocket();
        state = IDLE;
    }

    private void closeSocket() {
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        sock = null;
        out = null;
    }

    private void run(String host, int my, String proto) {
        boolean gotFrame = false;
        try {
            SSLSocket s = TlsUtil.open(ctx, host, 6466, 6000, proto, TlsUtil.variant(ctx));
            if (my != gen) { s.close(); return; }
            sock = s;
            out = s.getOutputStream();
            InputStream in = s.getInputStream();
            s.setSoTimeout(0);
            while (my == gen) {
                List<Pb.Field> f = Pb.parse(Pb.readFrame(in));
                gotFrame = true;
                for (Pb.Field x : f) {
                    switch (x.num) {
                        case 1:   // TV asks who we are
                            send(new Pb.W().m(1, new Pb.W().i(1, 622).m(2, new Pb.W()
                                    .s(1, "Android").s(2, "Phone").i(3, 1).s(4, "1")
                                    .s(5, "com.bhaskar.tvremote").s(6, "2.0.0"))).framed());
                            break;
                        case 2:   // TV activates the session
                            send(new Pb.W().m(2, new Pb.W().i(1, 622)).framed());
                            if (my == gen) setState(READY, "");
                            break;
                        case 8: { // ping
                            Pb.Field v = x.data == null ? null : Pb.find(Pb.parse(x.data), 1);
                            send(new Pb.W().m(9, new Pb.W().i(1, v == null ? 0 : v.v)).framed());
                            break;
                        }
                        case 40:
                            if (my == gen && state != READY) setState(READY, "");
                            break;
                        case 50: {
                            if (x.data == null) break;
                            List<Pb.Field> vf = Pb.parse(x.data);
                            Pb.Field mx = Pb.find(vf, 6), lv = Pb.find(vf, 7), mu = Pb.find(vf, 8);
                            ls.onVolume(lv == null ? 0 : (int) lv.v, mx == null ? 0 : (int) mx.v, mu != null && mu.v != 0);
                            break;
                        }
                        default:
                            break;
                    }
                }
            }
        } catch (Exception e) {
            if (my != gen) return;
            lastError = TlsUtil.describe(e);
            Trace.add("remote session ended: " + lastError);
            boolean unreachable = e instanceof ConnectException || e instanceof SocketTimeoutException
                    || e instanceof NoRouteToHostException || e instanceof UnknownHostException;
            if (!unreachable && !gotFrame && proto == null) {
                run(host, my, "TLSv1.2");   // some TVs only talk the older TLS version
                return;
            }
            if (unreachable) setState(FAILED, "net");
            else setState(FAILED, gotFrame ? "net" : "pair");
        } finally {
            if (my == gen) closeSocket();
        }
    }

    private void send(final byte[] framed) {
        io.execute(() -> {
            try {
                OutputStream o = out;
                if (o == null) return;
                synchronized (wlock) {
                    o.write(framed);
                    o.flush();
                }
            } catch (IOException ignored) {
                // the reader thread notices the broken connection and reports it
            }
        });
    }

    void key(int code) {
        send(new Pb.W().m(10, new Pb.W().i(1, code).i(2, 3)).framed());   // direction 3 = short press
    }

    void openLink(String link) {
        send(new Pb.W().m(90, new Pb.W().s(1, link)).framed());
    }

    /** Types text into the TV's focused text field, one key at a time. */
    void type(final String text) {
        new Thread(() -> {
            for (int i = 0; i < text.length(); i++) {
                int k = Keys.forChar(text.charAt(i));
                if (k > 0) {
                    key(k);
                    try { Thread.sleep(45); } catch (InterruptedException e) { return; }
                }
            }
        }, "tv-type").start();
    }
}
