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
        final List<NsdManager.DiscoveryListener> started = new ArrayList<>();
        for (final String type : new String[]{"_googlecast._tcp", "_androidtvremote2._tcp"}) {
            NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int code) { lines.add(type + ": could not start (error " + code + ")"); }
                @Override public void onStopDiscoveryFailed(String t, int code) { }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceLost(NsdServiceInfo s) { }
                @Override public void onServiceFound(NsdServiceInfo s) { lines.add(type + ": saw '" + s.getServiceName() + "'"); }
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
        StringBuilder sb = new StringBuilder("Android's own finder:\n");
        if (lines.isEmpty()) sb.append("  saw nothing\n");
        synchronized (lines) {
            for (String l : lines) sb.append("  ").append(l).append('\n');
        }
        return sb.toString();
    }
}
