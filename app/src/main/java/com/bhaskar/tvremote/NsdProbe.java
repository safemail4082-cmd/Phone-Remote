package com.bhaskar.tvremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Asks Android's own service finder what it can see, for the report. */
final class NsdProbe {

    static String run(Context ctx, int totalMs) {
        final NsdManager nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return "Android's own finder: not available\n";
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        final List<NsdServiceInfo> seen = Collections.synchronizedList(new ArrayList<NsdServiceInfo>());
        final List<NsdManager.DiscoveryListener> started = new ArrayList<>();
        for (final String type : new String[]{"_googlecast._tcp", "_androidtvremote2._tcp"}) {
            NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int code) { lines.add(type + ": could not start (error " + code + ")"); }
                @Override public void onStopDiscoveryFailed(String t, int code) { }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceLost(NsdServiceInfo s) { }
                @Override public void onServiceFound(NsdServiceInfo s) { lines.add(type + ": saw '" + s.getServiceName() + "'"); seen.add(s); }
            };
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, dl);
                started.add(dl);
            } catch (Exception e) {
                lines.add(type + ": " + TlsUtil.describe(e));
            }
        }
        try { Thread.sleep(totalMs); } catch (InterruptedException ignored) { }
        for (NsdManager.DiscoveryListener dl : started) {
            try { nsd.stopServiceDiscovery(dl); } catch (Exception ignored) { }
        }
        // find out the address of each device that was seen
        List<NsdServiceInfo> copy;
        synchronized (seen) { copy = new ArrayList<>(seen); }
        for (final NsdServiceInfo si : copy) {
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            try {
                nsd.resolveService(si, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int code) {
                        lines.add("  could not resolve '" + i.getServiceName() + "' (error " + code + ")");
                        done.countDown();
                    }

                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        lines.add("  '" + Discovery.attr(i, "fn") + "' model " + Discovery.attr(i, "md")
                                + " is at " + (i.getHost() == null ? "?" : i.getHost().getHostAddress()) + " port " + i.getPort());
                        done.countDown();
                    }
                });
                done.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                lines.add("  resolve error " + TlsUtil.describe(e));
            }
        }
        StringBuilder sb = new StringBuilder("Android's own finder:\n");
        if (lines.isEmpty()) sb.append("  saw nothing\n");
        synchronized (lines) {
            for (String l : lines) sb.append("  ").append(l).append('\n');
        }
        return sb.toString();
    }
}
