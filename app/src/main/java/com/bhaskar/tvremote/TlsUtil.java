package com.bhaskar.tvremote;

import android.content.Context;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** TLS helper: the TV wants a client certificate and presents a self-signed one itself. */
final class TlsUtil {
    private static final char[] PW = "atvremote".toCharArray();
    private static SSLContext ctx;
    private static X509Certificate clientCert;

    private static synchronized void init(Context c) throws Exception {
        if (ctx != null) return;
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = c.getAssets().open("client.p12")) {
            ks.load(in, PW);
        }
        Enumeration<String> al = ks.aliases();
        String alias = al.nextElement();
        clientCert = (X509Certificate) ks.getCertificate(alias);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PW);
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(kmf.getKeyManagers(), trustAll, null);
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
        init(c);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setSoTimeout(timeoutMs);
        raw.setTcpNoDelay(true);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
        s.setUseClientMode(true);
        s.startHandshake();
        return s;
    }
}
