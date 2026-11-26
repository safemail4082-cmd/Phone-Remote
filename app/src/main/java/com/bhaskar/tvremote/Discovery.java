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
import java.util.concurrent.atomic.AtomicInteger;

/** Finds Android TVs on the Wi-Fi using mDNS (no probing of the TV's ports). */
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

    void start(final Context c, final Cb cb) {
        stopped = false;
        // Android's own finder (works on some phones, silently fails on others)
        try {
            nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
            dl = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int e) { Trace.add("android finder not available (" + e + ")"); }
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

        // our own mDNS question (the reliable path)
        new Thread(() -> {
            final AtomicInteger count = new AtomicInteger();
            try {
                Mdns.query(c, 4000, (name, host) -> {
                    count.incrementAndGet();
                    if (!stopped) cb.found(name, host);
                });
            } catch (Exception e) {
                Trace.add("search error: " + TlsUtil.describe(e));
            }
            // some routers do not pass mDNS between devices: fall back to a quick look for the remote port
            if (count.get() == 0 && !stopped) {
                Trace.add("search: nothing answered, trying a quick network scan");
                scanPorts(cb);
            }
            cb.finished();
        }, "mdns").start();
    }

    /** Looks at the Wi-Fi's addresses for devices with the remote port or the Chromecast port open. */
    private void scanPorts(final Cb cb) {
        for (NetScan.Hit h : NetScan.run(new int[]{6466, 8008}, 450)) {
            if (stopped) return;
            if (h.open.contains(6466) || h.open.contains(8008)) {
                String name = h.castName.isEmpty() ? "Android TV" : h.castName;
                Trace.add("scan: " + h.host + " open " + h.open + (h.castName.isEmpty() ? "" : " name " + h.castName));
                cb.found(name, h.host);
            }
        }
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
        String ip = localIp();
        return ip == null ? null : ip.substring(0, ip.lastIndexOf('.') + 1);
    }

    static String localIp() {
        try {
            ArrayList<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (int pass = 0; pass < 2; pass++) {
                for (NetworkInterface ni : all) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    String n = ni.getName();
                    boolean wifi = n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth");
                    if (pass == 0 && !wifi) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
