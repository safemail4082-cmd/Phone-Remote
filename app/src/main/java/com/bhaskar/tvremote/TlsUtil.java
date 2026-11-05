package com.bhaskar.tvremote;

import android.content.Context;

import java.io.InputStream;
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

/** TLS helper: the TV wants a client certificate and presents a self-signed one itself. */
final class TlsUtil {
    private static final char[] PW = "atvremote".toCharArray();
    private static SSLContext ctx;
    private static X509Certificate clientCert;

    /**
     * Always offers our certificate. The default key manager would silently skip it when the TV
     * lists trusted issuers, and then the TV just hangs up.
     */
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
        @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return alias; }
        @Override public String chooseEngineClientAlias(String[] t, Principal[] i, SSLEngine e) { return alias; }
        @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
        @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
        @Override public X509Certificate[] getCertificateChain(String a) { return chain; }
        @Override public PrivateKey getPrivateKey(String a) { return key; }
    }

    private static synchronized void init(Context c) throws Exception {
        if (ctx != null) return;
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = c.getAssets().open("client.p12")) {
            ks.load(in, PW);
        }
        String alias = ks.aliases().nextElement();
        clientCert = (X509Certificate) ks.getCertificate(alias);
        PrivateKey pk = (PrivateKey) ks.getKey(alias, PW);
        Certificate[] raw = ks.getCertificateChain(alias);
        X509Certificate[] chain = new X509Certificate[raw.length];
        for (int i = 0; i < raw.length; i++) chain[i] = (X509Certificate) raw[i];
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(new KeyManager[]{new FixedKm(alias, pk, chain)}, trustAll, null);
        ctx = sc;
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

    static X509Certificate clientCertificate(Context c) throws Exception {
        init(c);
        return clientCert;
    }

    static SSLSocket open(Context c, String host, int port, int timeoutMs) throws Exception {
        return open(c, host, port, timeoutMs, null);
    }

    /** proto == null uses the phone's default; "TLSv1.2" forces the older protocol some TVs prefer. */
    static SSLSocket open(Context c, String host, int port, int timeoutMs, String proto) throws Exception {
        init(c);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setSoTimeout(timeoutMs);
        raw.setTcpNoDelay(true);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
        s.setUseClientMode(true);
        if (proto != null) s.setEnabledProtocols(new String[]{proto});
        s.startHandshake();
        return s;
    }
}
