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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Plain mDNS (multicast DNS) client. Finds Android TVs the way other remote apps do and can also
 * list what every device on the Wi-Fi announces, which helps when a TV is not where we expect.
 * Nothing is sent to the TV's TCP ports.
 */
final class Mdns {

    interface Cb { void found(String name, String host); }

    private static final String SERVICE = "_androidtvremote2._tcp.local";

    private static final class Sock {
        MulticastSocket ms;
        WifiManager.MulticastLock lock;
        InetAddress group;
    }

    private static Sock open(Context ctx) throws Exception {
        Sock s = new Sock();
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            s.lock = wm.createMulticastLock("tvremote-mdns");
            s.lock.setReferenceCounted(false);
            s.lock.acquire();
        }
        s.group = InetAddress.getByName("224.0.0.251");
        NetworkInterface wifi = wifiInterface();
        try {
            s.ms = makeSocket(ctx, 5353);
            Trace.add("search: listening on port 5353");
        } catch (Exception e) {
            if (s.ms != null) s.ms.close();
            s.ms = makeSocket(ctx, 0);
            Trace.add("search: using a private port");
        }
        s.ms.setTimeToLive(255);
        if (wifi != null) {
            try { s.ms.setNetworkInterface(wifi); } catch (Exception ignored) { }
        }
        try {
            if (wifi != null) s.ms.joinGroup(new InetSocketAddress(s.group, 5353), wifi);
            else s.ms.joinGroup(s.group);
        } catch (Exception e) {
            Trace.add("search: could not join the group (" + e.getMessage() + ")");
        }
        s.ms.setSoTimeout(300);
        return s;
    }

    private static void close(Sock s) {
        if (s == null) return;
        try { if (s.ms != null) s.ms.close(); } catch (Exception ignored) { }
        try { if (s.lock != null && s.lock.isHeld()) s.lock.release(); } catch (Exception ignored) { }
    }

    /** Reports every Android TV remote service that answers. */
    static void query(Context ctx, int totalMs, Cb cb) throws Exception {
        Sock s = open(ctx);
        try {
            byte[] q = buildQuery(SERVICE);
            long end = SystemClock.uptimeMillis() + totalMs;
            long nextSend = 0;
            int sends = 0;
            Set<String> seen = new HashSet<>();
            byte[] buf = new byte[4096];
            while (SystemClock.uptimeMillis() < end) {
                long now = SystemClock.uptimeMillis();
                if (now >= nextSend && sends < 5) {
                    s.ms.send(new DatagramPacket(q, q.length, s.group, 5353));
                    sends++;
                    nextSend = now + 1200;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.ms.receive(p);
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
            close(s);
        }
    }

    /** Lists what each device on the Wi-Fi announces (service types, ports, addresses). */
    static String survey(Context ctx, int totalMs) {
        Sock s = null;
        TreeMap<String, TreeSet<String>> seen = new TreeMap<>();
        try {
            s = open(ctx);
            byte[][] qs = {
                    buildQuery("_services._dns-sd._udp.local"),
                    buildQuery(SERVICE),
                    buildQuery("_androidtvremote._tcp.local"),
                    buildQuery("_googlecast._tcp.local")};
            long end = SystemClock.uptimeMillis() + totalMs;
            long nextSend = 0;
            int rounds = 0;
            byte[] buf = new byte[4096];
            while (SystemClock.uptimeMillis() < end) {
                long now = SystemClock.uptimeMillis();
                if (now >= nextSend && rounds < 3) {
                    for (byte[] q : qs) s.ms.send(new DatagramPacket(q, q.length, s.group, 5353));
                    rounds++;
                    nextSend = now + 1300;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.ms.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (!(p.getAddress() instanceof Inet4Address)) continue;
                collect(buf, p.getLength(), p.getAddress().getHostAddress(), seen);
            }
        } catch (Exception e) {
            return "Network search error: " + TlsUtil.describe(e) + "\n";
        } finally {
            close(s);
        }
        StringBuilder sb = new StringBuilder("Devices announcing services on your Wi-Fi:\n");
        if (seen.isEmpty()) sb.append("  none heard (multicast may be blocked)\n");
        for (Map.Entry<String, TreeSet<String>> en : seen.entrySet()) {
            sb.append("  ").append(en.getKey()).append('\n');
            int n = 0;
            for (String line : en.getValue()) {
                if (n++ >= 8) { sb.append("      ...\n"); break; }
                sb.append("      ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers
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

    private static byte[] buildQuery(String fqdn) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0); o.write(0);      // id
        o.write(0); o.write(0);      // flags: standard query
        o.write(0); o.write(1);      // one question
        o.write(0); o.write(0);      // answers
        o.write(0); o.write(0);      // authority
        o.write(0); o.write(0);      // additional
        for (String label : fqdn.split("\\.")) {
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

    /** Walks all records of a DNS packet; returns false if the packet is malformed. */
    private interface RecordSink { void record(String name, int type, int rd, int rdlen); }

    private static boolean walk(byte[] p, int len, RecordSink sink) {
        if (len < 12) return false;
        int qd = u16(p, 4), total = u16(p, 6) + u16(p, 8) + u16(p, 10);
        int[] ref = {12};
        for (int i = 0; i < qd; i++) {
            if (readName(p, ref, len) == null) return false;
            ref[0] += 4;
            if (ref[0] > len) return false;
        }
        for (int i = 0; i < total; i++) {
            if (ref[0] >= len) break;
            String name = readName(p, ref, len);
            if (name == null) return false;
            int pos = ref[0];
            if (pos + 10 > len) return false;
            int type = u16(p, pos), rdlen = u16(p, pos + 8), rd = pos + 10;
            ref[0] = rd + rdlen;
            if (ref[0] > len) return false;
            sink.record(name, type, rd, rdlen);
        }
        return true;
    }

    /** Returns the TV's name when this packet announces an _androidtvremote2 service, else null. */
    private static String instanceName(final byte[] p, final int len) {
        final String[] out = {null};
        walk(p, len, (name, type, rd, rdlen) -> {
            if (out[0] != null) return;
            String lname = name.toLowerCase(Locale.US);
            if (type == 12 && lname.equals(SERVICE)) {
                int[] r2 = {rd};
                String inst = readName(p, r2, len);
                if (inst != null) out[0] = instanceOf(inst);
            } else if ((type == 33 || type == 16) && lname.endsWith("." + SERVICE)) {
                out[0] = instanceOf(name);
            }
        });
        return out[0];
    }

    /** Adds a short description of each interesting record to the list for this sender. */
    private static void collect(final byte[] p, final int len, String ip, Map<String, TreeSet<String>> out) {
        final TreeSet<String> lines = new TreeSet<>();
        walk(p, len, (name, type, rd, rdlen) -> {
            String lname = name.toLowerCase(Locale.US);
            if (type == 12) {
                int[] r2 = {rd};
                String t = readName(p, r2, len);
                if (t == null) return;
                if (lname.equals("_services._dns-sd._udp.local")) lines.add("service type: " + t.replace(".local", ""));
                else lines.add("name: " + t.replace(".local", ""));
            } else if (type == 33 && rdlen >= 7) {
                int[] r2 = {rd + 6};
                String target = readName(p, r2, len);
                if (target != null) lines.add("port " + u16(p, rd + 4) + "  " + name.replace(".local", ""));
            } else if (type == 1 && rdlen == 4) {
                lines.add("address " + (p[rd] & 0xFF) + "." + (p[rd + 1] & 0xFF) + "." + (p[rd + 2] & 0xFF) + "." + (p[rd + 3] & 0xFF)
                        + "  " + name.replace(".local", ""));
            }
        });
        if (lines.isEmpty()) return;
        TreeSet<String> set = out.get(ip);
        if (set == null) { set = new TreeSet<>(); out.put(ip, set); }
        set.addAll(lines);
    }
}
