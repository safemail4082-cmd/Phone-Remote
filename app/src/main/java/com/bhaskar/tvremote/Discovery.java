package com.bhaskar.tvremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Finds Android TVs on the Wi-Fi: mDNS first, plus a quick port scan as a backup. */
final class Discovery {

    interface Cb {
        void found(String name, String host);

        void finished();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private NsdManager nsd;
    private NsdManager.DiscoveryListener dl;
    private boolean resolving;
    private volatile boolean stopped;

    void start(Context c, final Cb cb) {
        stopped = false;
        try {
            nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
            dl = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int e) { }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceLost(NsdServiceInfo s) { }

                @Override
                public void onServiceFound(final NsdServiceInfo s) {
                    main.post(() -> { queue.add(s); pump(cb); });
                }
            };
            nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, dl);
        } catch (Exception ignored) { }
    }

    private void pump(final Cb cb) {
        if (stopped || resolving || queue.isEmpty() || nsd == null) return;
        resolving = true;
        NsdServiceInfo s = queue.poll();
        try {
            nsd.resolveService(s, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo si, int code) {
                    main.post(() -> { resolving = false; pump(cb); });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo si) {
                    InetAddress a = si.getHost();
                    if (a instanceof Inet4Address && !stopped) cb.found(si.getServiceName(), a.getHostAddress());
                    main.post(() -> { resolving = false; pump(cb); });
                }
            });
        } catch (Exception e) {
            resolving = false;
        }
    }

    void stop() {
        stopped = true;
        try { if (nsd != null && dl != null) nsd.stopServiceDiscovery(dl); } catch (Exception ignored) { }
        dl = null;
        queue.clear();
        resolving = false;
    }

    static String localPrefix() {
        try {
            ArrayList<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (int pass = 0; pass < 2; pass++) {
                for (NetworkInterface ni : all) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    String n = ni.getName();
                    boolean wifi = n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth");
                    if (pass == 0 && !wifi) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                            String ip = a.getHostAddress();
                            return ip.substring(0, ip.lastIndexOf('.') + 1);
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Looks for devices answering on the remote-control port (6466). */
    static void scan(final Cb cb) {
        new Thread(() -> {
            final String prefix = localPrefix();
            if (prefix != null) {
                ExecutorService ex = Executors.newFixedThreadPool(48);
                for (int i = 1; i < 255; i++) {
                    final String host = prefix + i;
                    ex.submit(() -> {
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(host, 6466), 700);
                            cb.found("Android TV", host);
                        } catch (Exception ignored) { }
                    });
                }
                ex.shutdown();
                try { ex.awaitTermination(25, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            cb.finished();
        }, "scan").start();
    }
}
