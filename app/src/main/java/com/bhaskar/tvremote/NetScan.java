package com.bhaskar.tvremote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Walks the Wi-Fi's addresses once and records which ports answer. Works even when mDNS is blocked. */
final class NetScan {

    static final class Hit {
        String host;
        boolean alive;                       // something answered (even if only to say "closed")
        final TreeSet<Integer> open = new TreeSet<>();
        String castName = "";                // name reported by a Chromecast-built-in TV
    }

    private static void mark(Map<Integer, Hit> map, int last, String host, int openPort, boolean alive) {
        synchronized (map) {
            Hit h = map.get(last);
            if (h == null) { h = new Hit(); h.host = host; map.put(last, h); }
            if (openPort > 0) { h.open.add(openPort); h.alive = true; }
            if (alive) h.alive = true;
        }
    }

    static List<Hit> run(final int[] ports, final int timeoutMs) {
        String prefix = Discovery.localPrefix();
        String own = Discovery.localIp();
        if (prefix == null) return new ArrayList<>();
        final Map<Integer, Hit> map = new TreeMap<>();
        ExecutorService ex = Executors.newFixedThreadPool(96);
        for (int i = 1; i < 255; i++) {
            final String host = prefix + i;
            if (host.equals(own)) continue;
            final int last = i;
            for (final int port : ports) {
                ex.submit(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, port), timeoutMs);
                        mark(map, last, host, port, true);
                    } catch (ConnectException e) {
                        if (String.valueOf(e.getMessage()).contains("ECONNREFUSED")) mark(map, last, host, 0, true);
                    } catch (Exception ignored) { }
                });
            }
        }
        ex.shutdown();
        try { ex.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        List<Hit> out;
        synchronized (map) { out = new ArrayList<>(map.values()); }
        for (Hit h : out) {
            if (h.open.contains(8008)) h.castName = castName(h.host);
        }
        return out;
    }

    /** Name of a Chromecast-built-in device (most Android TVs), or "". */
    static String castName(String host) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":8008/setup/eureka_info?options=detail").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = br.read(buf)) > 0 && sb.length() < 20000) sb.append(buf, 0, n);
            br.close();
            Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(sb);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) { }
        return "";
    }
}
