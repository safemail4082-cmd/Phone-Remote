package com.bhaskar.tvremote;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;

/** One-time pairing with the TV: the TV shows a 6-character code that you type on the phone. */
final class Pairing {

    interface Cb {
        /** progress text for the "please wait" dialog */
        void status(String text);

        /** error == null the first time; later it says why the previous code was refused */
        void needCode(String error);

        void done(boolean ok, String message);
    }

    private final Context ctx;
    private final String host;
    private final Cb cb;
    private final BlockingQueue<String> codes = new ArrayBlockingQueue<>(1);
    private volatile boolean cancelled;
    private volatile SSLSocket sock;

    Pairing(Context c, String host, Cb cb) {
        this.ctx = c.getApplicationContext();
        this.host = host;
        this.cb = cb;
    }

    void start() { new Thread(this::run, "pairing").start(); }

    void submit(String code) { codes.clear(); codes.offer(code == null ? "" : code); }

    void cancel() {
        cancelled = true;
        codes.clear();
        codes.offer("");
        close();
    }

    private void close() {
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
    }

    private static byte[] msg(int field, Pb.W inner) {
        return new Pb.W().i(1, 2).i(2, 200).m(field, inner).framed();
    }

    private static Pb.W encoding() { return new Pb.W().i(1, 3).i(2, 6); }   // hexadecimal, 6 symbols

    private static byte[] strip(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) return Arrays.copyOfRange(b, 1, b.length);
        return b;
    }

    static String names(List<Pb.Field> fs) {
        StringBuilder sb = new StringBuilder();
        for (Pb.Field f : fs) {
            String n;
            switch (f.num) {
                case 1: n = "version"; break;
                case 2: n = "status=" + f.v; break;
                case 11: n = "request-ack"; break;
                case 20: n = "options"; break;
                case 31: n = "config-ack"; break;
                case 41: n = "secret-ack"; break;
                default: n = "field" + f.num; break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
        }
        return sb.toString();
    }

    private void run() {
        try {
            int pref = TlsUtil.variant(ctx);
            int[] order = pref == 0 ? new int[]{0, 1} : new int[]{1, 0};
            String[] protos = {null, "TLSv1.2"};
            for (int v : order) {
                for (String proto : protos) {
                    if (cancelled) return;
                    if (session(v, proto) != null) return;   // finished (success or a clear failure)
                    // otherwise the TV hung up before answering: try the next certificate / TLS version
                }
            }
            cb.done(false, "The TV hung up every time without answering.");
        } catch (Exception e) {
            Trace.add("error: " + TlsUtil.describe(e));
            if (!cancelled) cb.done(false, "Pairing failed.\n" + TlsUtil.describe(e));
        } finally {
            close();
        }
    }

    /** Returns null when the TV closed the link before sending anything (caller may retry). */
    private Boolean session(int variant, String proto) throws Exception {
        close();
        cb.status("Connecting securely to your TV...");
        SSLSocket s = TlsUtil.openPatient(ctx, host, 6467, 7000, proto, variant, 20000);
        sock = s;
        s.setSoTimeout(8000);
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();

        out.write(msg(10, new Pb.W().s(1, "androidtvremote2").s(2, "Android phone")));
        out.flush();
        Trace.add("sent pairing request");
        cb.status("Waiting for the TV to answer...");

        // handshake: request -> option -> configuration -> (TV shows the code)
        boolean first = true;
        while (true) {
            List<Pb.Field> f;
            try {
                f = Pb.parse(Pb.readFrame(in));
            } catch (IOException e) {
                if (first) {
                    Trace.add("TV closed the link without answering (" + e.getMessage() + ")");
                    return null;
                }
                throw e;
            }
            if (first) { s.setSoTimeout(20000); first = false; }
            Trace.add("TV sent: " + names(f));
            Pb.Field st = Pb.find(f, 2);
            if (st != null && st.v != 200) {
                cb.done(false, "The TV refused pairing (code " + st.v + ").");
                return Boolean.TRUE;
            }
            if (Pb.find(f, 11) != null) {
                out.write(msg(20, new Pb.W().m(1, encoding()).i(3, 1)));
                out.flush();
                Trace.add("sent options");
            } else if (Pb.find(f, 20) != null) {
                out.write(msg(30, new Pb.W().m(1, encoding()).i(2, 1)));
                out.flush();
                Trace.add("sent configuration");
            } else if (Pb.find(f, 31) != null) {
                break;
            }
        }

        RSAPublicKey cp = (RSAPublicKey) TlsUtil.clientCertificate(ctx, variant).getPublicKey();
        X509Certificate server = (X509Certificate) s.getSession().getPeerCertificates()[0];
        if (!(server.getPublicKey() instanceof RSAPublicKey)) {
            cb.done(false, "This TV uses a certificate type that is not supported.");
            return Boolean.TRUE;
        }
        RSAPublicKey sp = (RSAPublicKey) server.getPublicKey();

        String error = null;
        byte[] secret;
        while (true) {
            Trace.add("waiting for the code");
            cb.needCode(error);
            String code = codes.poll(180, TimeUnit.SECONDS);
            if (cancelled || code == null || code.isEmpty()) {
                cb.done(false, "Pairing cancelled.");
                return Boolean.TRUE;
            }
            code = code.trim().toUpperCase();
            if (!code.matches("[0-9A-F]{6}")) { error = "The code has 6 characters (0-9, A-F)."; continue; }
            byte[] cb3 = new byte[3];
            for (int i = 0; i < 3; i++) cb3[i] = (byte) Integer.parseInt(code.substring(i * 2, i * 2 + 2), 16);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(strip(cp.getModulus()));
            md.update(strip(cp.getPublicExponent()));
            md.update(strip(sp.getModulus()));
            md.update(strip(sp.getPublicExponent()));
            md.update(cb3, 1, 2);
            secret = md.digest();
            if (secret[0] != cb3[0]) { error = "That code was not right. Check the TV screen."; continue; }
            break;
        }

        cb.status("Checking the code with your TV...");
        out.write(msg(40, new Pb.W().b(1, secret)));
        out.flush();
        Trace.add("sent secret");
        while (true) {
            List<Pb.Field> f = Pb.parse(Pb.readFrame(in));
            Trace.add("TV sent: " + names(f));
            Pb.Field st = Pb.find(f, 2);
            if (st != null && st.v != 200) {
                cb.done(false, "The TV did not accept the code.");
                return Boolean.TRUE;
            }
            if (Pb.find(f, 41) != null) {
                TlsUtil.saveVariant(ctx, variant);
                Trace.add("paired (certificate " + variant + ")");
                cb.done(true, "Paired");
                return Boolean.TRUE;
            }
        }
    }
}
