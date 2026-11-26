package com.bhaskar.tvremote;

import android.content.Context;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;

import javax.net.ssl.SSLSocket;

/** Builds the plain-text connection report shown in error boxes and on the Test connection button. */
final class Diag {

    private static String scanReport() {
        if (Discovery.localIp() == null) return "Network scan: phone is not on Wi-Fi\n";
        List<NetScan.Hit> hits = NetScan.run(new int[]{6466, 6467, 6091, 9551, 9552, 8008, 8009, 6095, 5555}, 400);
        StringBuilder sb = new StringBuilder("Devices found on your Wi-Fi (ports checked: 6466 6467 6091 9551 9552 8008 8009 6095 5555):\n");
        StringBuilder quiet = new StringBuilder();
        for (NetScan.Hit h : hits) {
            if (h.open.isEmpty()) {
                if (quiet.length() > 0) quiet.append(", ");
                quiet.append(h.host.substring(h.host.lastIndexOf('.') + 1));
                continue;
            }
            sb.append("  ").append(h.host).append("  open ").append(h.open);
            if (!h.castName.isEmpty()) sb.append("  name: ").append(h.castName);
            sb.append('\n');
        }
        if (quiet.length() > 0) sb.append("  other devices (no tested port open): .").append(quiet).append('\n');
        return sb.toString();
    }

    static String report(final Context ctx, String host, boolean probe) {
        StringBuilder sb = new StringBuilder();
        String prefix = Discovery.localPrefix();
        sb.append("Phone Wi-Fi: ").append(prefix == null ? "NOT on Wi-Fi" : prefix + "x").append('\n');
        sb.append("TV address used: ").append(host.isEmpty() ? "none" : host).append('\n');

        final String[] nsd = {""}, scan = {""};
        Thread t1 = new Thread(() -> nsd[0] = NsdProbe.run(ctx, 4000), "nsd-probe");
        Thread t2 = new Thread(() -> scan[0] = scanReport(), "scan-probe");
        t1.start();
        t2.start();
        String survey = Mdns.survey(ctx, 4500);
        try {
            t1.join(8000);
            t2.join(30000);
        } catch (InterruptedException ignored) { }
        sb.append(scan[0]).append(nsd[0]).append(survey);

        if (!host.isEmpty()) {
            for (int port : new int[]{6466, 6467, 6091, 6095, 9551, 9552}) {
                sb.append("Port ").append(port).append(" on ").append(host).append(": ")
                        .append(portState(host, port)).append('\n');
            }
            sb.append(wideScan(host));
            if (probe) {
                for (int v = 0; v < TlsUtil.VARIANTS; v++) {
                    sb.append("Pairing test (certificate ").append(v).append("): ")
                            .append(probePairing(ctx, host, v)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Every port from 1 to 10000 on the TV: shows which services it really offers. */
    private static String wideScan(final String host) { return scanRange(host, 10000, 400, 200); }

    /** Every port from 1 to 65535: run this while the 4-digit code is showing on the TV. */
    static String deepScan(final String host) {
        long t = System.currentTimeMillis();
        String r = scanRange(host, 65535, 300, 300);
        return r + "(took " + (System.currentTimeMillis() - t) / 1000 + " s)\n";
    }

    private static String scanRange(final String host, final int max, final int timeoutMs, int threads) {
        final java.util.concurrent.ConcurrentSkipListSet<Integer> open = new java.util.concurrent.ConcurrentSkipListSet<>();
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int p = 1; p <= max; p++) {
            final int port = p;
            ex.submit(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, port), timeoutMs);
                    open.add(port);
                } catch (Exception ignored) { }
            });
        }
        ex.shutdown();
        try { ex.awaitTermination(240, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return "ALL open ports on " + host + " (1-" + max + "): " + (open.isEmpty() ? "none" : open.toString()) + "\n";
    }

    private static String portState(String h, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(h, port), 1500);
            return "open";
        } catch (ConnectException e) {
            return "closed (refused)";
        } catch (SocketTimeoutException e) {
            return "no answer";
        } catch (Exception e) {
            return TlsUtil.describe(e);
        }
    }

    /** Opens the pairing channel, sends the pairing request and reports what the TV answers. */
    private static String probePairing(Context ctx, String h, int v) {
        try (SSLSocket s = TlsUtil.open(ctx, h, 6467, 6000, null, v)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(new Pb.W().i(1, 2).i(2, 200)
                    .m(10, new Pb.W().s(1, "atvremote").s(2, "Android phone")).framed());
            s.getOutputStream().flush();
            try {
                List<Pb.Field> f = Pb.parse(Pb.readFrame(s.getInputStream()));
                return "TV answered (" + Pairing.names(f) + ")";
            } catch (IOException e) {
                return "secure link OK but the TV did not answer - " + TlsUtil.describe(e);
            }
        } catch (Exception e) {
            return "failed - " + TlsUtil.describe(e);
        }
    }
}
