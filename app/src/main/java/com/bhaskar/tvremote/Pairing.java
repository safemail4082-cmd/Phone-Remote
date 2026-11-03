package com.bhaskar.tvremote;

import android.content.Context;

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

    private void run() {
        try {
            SSLSocket s = TlsUtil.open(ctx, host, 6467, 7000);
            sock = s;
            s.setSoTimeout(20000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write(msg(10, new Pb.W().s(1, "atvremote").s(2, "Android phone")));
            out.flush();

            // handshake: request -> option -> configuration -> (TV shows the code)
            while (true) {
                List<Pb.Field> f = Pb.parse(Pb.readFrame(in));
                Pb.Field st = Pb.find(f, 2);
                if (st != null && st.v != 200) { cb.done(false, "The TV refused pairing (code " + st.v + ")."); return; }
                if (Pb.find(f, 11) != null) {
                    out.write(msg(20, new Pb.W().m(1, encoding()).i(3, 1)));
                    out.flush();
                } else if (Pb.find(f, 20) != null) {
                    out.write(msg(30, new Pb.W().m(1, encoding()).i(2, 1)));
                    out.flush();
                } else if (Pb.find(f, 31) != null) {
                    break;
                }
            }

            RSAPublicKey cp = (RSAPublicKey) TlsUtil.clientCertificate(ctx).getPublicKey();
            X509Certificate server = (X509Certificate) s.getSession().getPeerCertificates()[0];
            if (!(server.getPublicKey() instanceof RSAPublicKey)) {
                cb.done(false, "This TV uses a certificate type that is not supported.");
                return;
            }
            RSAPublicKey sp = (RSAPublicKey) server.getPublicKey();

            String error = null;
            byte[] secret;
            while (true) {
                cb.needCode(error);
                String code = codes.poll(180, TimeUnit.SECONDS);
                if (cancelled || code == null || code.isEmpty()) { cb.done(false, "Pairing cancelled."); return; }
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

            out.write(msg(40, new Pb.W().b(1, secret)));
            out.flush();
            while (true) {
                List<Pb.Field> f = Pb.parse(Pb.readFrame(in));
                Pb.Field st = Pb.find(f, 2);
                if (st != null && st.v != 200) { cb.done(false, "The TV did not accept the code."); return; }
                if (Pb.find(f, 41) != null) { cb.done(true, "Paired"); return; }
            }
        } catch (Exception e) {
            if (!cancelled) cb.done(false, "Pairing failed: " + e.getMessage());
        } finally {
            close();
        }
    }
}
