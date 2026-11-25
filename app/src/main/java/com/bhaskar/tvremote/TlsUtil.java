package com.bhaskar.tvremote;

import android.content.Context;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS helper. The TV wants a client certificate and presents a self-signed one itself.
 * Two certificate "variants" are built in: 0 = ordinary, 1 = plain minimal certificate in the
 * style used by other Android TV remote apps. Pairing tries both and remembers the one that works.
 */
final class TlsUtil {
    private static final char[] PW = "atvremote".toCharArray();
    static final int VARIANTS = 2;
    private static final SSLContext[] ctxs = new SSLContext[VARIANTS];
    private static final X509Certificate[] certs = new X509Certificate[VARIANTS];

    /** Always offers our certificate, even when the TV lists trusted issuers. */
    private static final class FixedKm extends X509ExtendedKeyManager {
        private final String alias;
        private final PrivateKey key;
        private final X509Certificate[] chain;

        FixedKm(String alias, PrivateKey key, X509Certificate[] chain) {
            this.alias = alias;
            this.key = key;
            this.chain = chain;
        }

        @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{alias}; }

        @Override
        public String chooseClientAlias(String[] t, Principal[] i, Socket s) {
            Trace.add("TV asked for a client certificate - offering ours");
            return alias;
        }

        @Override
        public String chooseEngineClientAlias(String[] t, Principal[] i, SSLEngine e) {
            Trace.add("TV asked for a client certificate - offering ours");
            return alias;
        }

        @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
        @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
        @Override public X509Certificate[] getCertificateChain(String a) { return chain; }
        @Override public PrivateKey getPrivateKey(String a) { return key; }
    }

    private static synchronized void init(Context c, int v) throws Exception {
        if (ctxs[v] != null) return;
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = c.getAssets().open(v == 0 ? "client.p12" : "client2.p12")) {
            ks.load(in, PW);
        }
        String alias = ks.aliases().nextElement();
        certs[v] = (X509Certificate) ks.getCertificate(alias);
        PrivateKey pk = (PrivateKey) ks.getKey(alias, PW);
        Certificate[] raw = ks.getCertificateChain(alias);
        X509Certificate[] chain = new X509Certificate[raw.length];
        for (int i = 0; i < raw.length; i++) chain[i] = (X509Certificate) raw[i];
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] ch, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] ch, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(new KeyManager[]{new FixedKm(alias, pk, chain)}, trustAll, null);
        ctxs[v] = sc;
    }

    static int variant(Context c) {
        return c.getApplicationContext().getSharedPreferences("remote", Context.MODE_PRIVATE).getInt("certv", 0);
    }

    static void saveVariant(Context c, int v) {
        c.getApplicationContext().getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putInt("certv", v).apply();
    }

    /** Short human-readable description of an error and its cause. */
    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append("  <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        return sb.toString();
    }

    static X509Certificate clientCertificate(Context c, int v) throws Exception {
        init(c, v);
        return certs[v];
    }

    static SSLSocket open(Context c, String host, int port, int timeoutMs) throws Exception {
        return open(c, host, port, timeoutMs, null, variant(c));
    }

    /** proto == null uses the phone's default; "TLSv1.2" forces the older protocol some TVs prefer. */
    static SSLSocket open(Context c, String host, int port, int timeoutMs, String proto, int v) throws Exception {
        init(c, v);
        Trace.add("connecting " + host + ":" + port + " (certificate " + v + ", "
                + (proto == null ? "default TLS" : proto) + ")");
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setSoTimeout(Math.max(timeoutMs, 12000));
        raw.setTcpNoDelay(true);
        SSLSocket s = (SSLSocket) ctxs[v].getSocketFactory().createSocket(raw, host, port, true);
        s.setUseClientMode(true);
        if (proto != null) s.setEnabledProtocols(new String[]{proto});
        s.startHandshake();
        Trace.add("secure link ok: " + s.getSession().getProtocol() + " / " + s.getSession().getCipherSuite());
        return s;
    }

    /** Like open(), but keeps trying for a while when the TV refuses the connection. */
    static SSLSocket openPatient(Context c, String host, int port, int timeoutMs, String proto, int v, int maxWaitMs)
            throws Exception {
        long end = SystemClock.uptimeMillis() + maxWaitMs;
        int n = 0;
        while (true) {
            try {
                return open(c, host, port, timeoutMs, proto, v);
            } catch (ConnectException e) {
                n++;
                Trace.add("TV refused the connection (try " + n + ")");
                if (SystemClock.uptimeMillis() + 1500 > end) throw e;
                Thread.sleep(1500);
            }
        }
    }
}
