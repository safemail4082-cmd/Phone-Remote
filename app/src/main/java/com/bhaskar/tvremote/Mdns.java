package com.bhaskar.tvremote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Finds Android TVs the way other remote apps do: a plain mDNS question for _androidtvremote2._tcp.
 * (Android's built-in service finder rejects that name on many phones because it is longer than 15
 * characters, so we speak mDNS ourselves.) Nothing is sent to the TV's TCP ports.
 */
final class Mdns {

    interface Cb { void found(String name, String host); }

    private static final String SERVICE = "_androidtvremote2._tcp.local";

    static void query(Context ctx, int totalMs, Cb cb) throws Exception {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wm != null) {
            lock = wm.createMulticastLock("tvremote-mdns");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        MulticastSocket ms = null;
        try {
            InetAddress group = InetAddress.getByName("224.0.0.251");
            NetworkInterface wifi = wifiInterface();
            try {
                ms = makeSocket(ctx, 5353);
                Trace.add("search: listening on port 5353");
            } catch (Exception e) {
                if (ms != null) ms.close();
                ms = makeSocket(ctx, 0);
                Trace.add("search: using a private port");
            }
            ms.setTimeToLive(255);
            if (wifi != null) {
                try { ms.setNetworkInterface(wifi); } catch (Exception ignored) { }
            }
            try {
                if (wifi != null) ms.joinGroup(new InetSocketAddress(group, 5353), wifi);
                else ms.joinGroup(group);
            } catch (Exception e) {
                Trace.add("search: could not join the group (" + e.getMessage() + ")");
            }
            ms.setSoTimeout(300);

            byte[] q = buildQuery();
            long end = SystemClock.uptimeMillis() + totalMs;
            long nextSend = 0;
            int sends = 0;
            Set<String> seen = new HashSet<>();
            byte[] buf = new byte[4096];
            while (SystemClock.uptimeMillis() < end) {
                long now = SystemClock.uptimeMillis();
                if (now >= nextSend && sends < 5) {
                    ms.send(new DatagramPacket(q, q.length, group, 5353));
                    sends++;
                    nextSend = now + 1200;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    ms.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (!(p.getAddress() instanceof Inet4Address)) continue;
                String name = instanceName(buf, p.getLength());
                if (name == null) continue;
                String host = p.getAddress().getHostAddress();
                if (seen.add(host)) {
                    Trace.add("search: found '" + name + "' at " + host);
                    cb.found(name, host);
                }
            }
            if (seen.isEmpty()) Trace.add("search: no TV answered");
        } finally {
            if (ms != null) ms.close();
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    /** A UDP socket tied to the Wi-Fi network (not mobile data). */
    @SuppressWarnings("deprecation")
    private static MulticastSocket makeSocket(Context ctx, int port) throws Exception {
        MulticastSocket s = new MulticastSocket(null);
        try {
            s.setReuseAddress(true);
            try {
                ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    for (Network n : cm.getAllNetworks()) {
                        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            n.bindSocket(s);
                            Trace.add("search: tied to the Wi-Fi network");
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Trace.add("search: could not tie to Wi-Fi (" + e.getMessage() + ")");
            }
            s.bind(new InetSocketAddress(port));
            return s;
        } catch (Exception e) {
            s.close();
            throw e;
        }
    }

    private static NetworkInterface wifiInterface() {
        try {
            NetworkInterface fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                if (ni.getName().startsWith("wlan")) return ni;
                if (fallback == null && ni.supportsMulticast()) {
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) fallback = ni;
                    }
                }
            }
            return fallback;
        } catch (Exception ignored) { }
        return null;
    }

    private static byte[] buildQuery() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0); o.write(0);      // id
        o.write(0); o.write(0);      // flags: standard query
        o.write(0); o.write(1);      // one question
        o.write(0); o.write(0);      // answers
        o.write(0); o.write(0);      // authority
        o.write(0); o.write(0);      // additional
        for (String label : new String[]{"_androidtvremote2", "_tcp", "local"}) {
            byte[] b = label.getBytes(StandardCharsets.UTF_8);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
        o.write(0); o.write(12);     // type PTR
        o.write(0x80); o.write(1);   // class IN, "please answer me directly"
        return o.toByteArray();
    }

    private static int u16(byte[] p, int i) { return ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF); }

    /** Reads a (possibly compressed) DNS name; posRef[0] ends up just after the name. */
    private static String readName(byte[] p, int[] posRef, int len) {
        StringBuilder sb = new StringBuilder();
        int pos = posRef[0];
        boolean jumped = false;
        int guard = 0;
        while (pos < len && guard++ < 64) {
            int l = p[pos] & 0xFF;
            if (l == 0) {
                pos++;
                if (!jumped) posRef[0] = pos;
                return sb.toString();
            }
            if ((l & 0xC0) == 0xC0) {
                if (pos + 1 >= len) return null;
                int ptr = ((l & 0x3F) << 8) | (p[pos + 1] & 0xFF);
                if (!jumped) posRef[0] = pos + 2;
                jumped = true;
                pos = ptr;
                continue;
            }
            pos++;
            if (pos + l > len) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(p, pos, l, StandardCharsets.UTF_8));
            pos += l;
        }
        return null;
    }

    private static String instanceOf(String fqdn) {
        String suffix = "." + SERVICE;
        if (fqdn.toLowerCase(Locale.US).endsWith(suffix)) return fqdn.substring(0, fqdn.length() - suffix.length());
        return fqdn;
    }

    /** Returns the TV's name when this packet announces an _androidtvremote2 service, else null. */
    private static String instanceName(byte[] p, int len) {
        if (len < 12) return null;
        int qd = u16(p, 4), total = u16(p, 6) + u16(p, 8) + u16(p, 10);
        int[] ref = {12};
        for (int i = 0; i < qd; i++) {
            if (readName(p, ref, len) == null) return null;
            ref[0] += 4;
            if (ref[0] > len) return null;
        }
        for (int i = 0; i < total; i++) {
            if (ref[0] >= len) break;
            String name = readName(p, ref, len);
            if (name == null) return null;
            int pos = ref[0];
            if (pos + 10 > len) return null;
            int type = u16(p, pos), rdlen = u16(p, pos + 8), rd = pos + 10;
            ref[0] = rd + rdlen;
            if (ref[0] > len) return null;
            String lname = name.toLowerCase(Locale.US);
            if (type == 12 && lname.equals(SERVICE)) {
                int[] r2 = {rd};
                String inst = readName(p, r2, len);
                if (inst != null) return instanceOf(inst);
            } else if ((type == 33 || type == 16) && lname.endsWith("." + SERVICE)) {
                return instanceOf(name);
            }
        }
        return null;
    }
}
