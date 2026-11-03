package com.bhaskar.tvremote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tiny protobuf writer/reader: just enough for the Android TV remote protocol. */
final class Pb {

    static final class Field {
        int num, wire;
        long v;
        byte[] data;
    }

    static final class W {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        private void varint(long v) {
            while ((v & ~0x7FL) != 0) {
                o.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            o.write((int) v);
        }

        W i(int field, long v) { varint(((long) field << 3)); varint(v); return this; }

        W b(int field, byte[] d) {
            varint(((long) field << 3) | 2);
            varint(d.length);
            o.write(d, 0, d.length);
            return this;
        }

        W s(int field, String s) {
            try { return b(field, s.getBytes("UTF-8")); } catch (Exception e) { return this; }
        }

        W m(int field, W inner) { return b(field, inner.bytes()); }

        byte[] bytes() { return o.toByteArray(); }

        /** length-prefixed ("delimited") form used on the wire */
        byte[] framed() {
            byte[] body = bytes();
            W l = new W();
            l.varint(body.length);
            byte[] pre = l.bytes();
            byte[] out = new byte[pre.length + body.length];
            System.arraycopy(pre, 0, out, 0, pre.length);
            System.arraycopy(body, 0, out, pre.length, body.length);
            return out;
        }
    }

    static long readVarint(InputStream in) throws IOException {
        long r = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("connection closed");
            r |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return r;
            shift += 7;
            if (shift > 63) throw new IOException("bad varint");
        }
    }

    static byte[] readFrame(InputStream in) throws IOException {
        long len = readVarint(in);
        if (len < 0 || len > 1000000) throw new IOException("bad frame length " + len);
        byte[] b = new byte[(int) len];
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("connection closed");
            off += n;
        }
        return b;
    }

    private static long[] vint(byte[] b, int p) {
        long r = 0;
        int shift = 0;
        while (p < b.length) {
            int x = b[p++];
            r |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return new long[]{r, p};
    }

    static List<Field> parse(byte[] b) {
        ArrayList<Field> out = new ArrayList<>();
        int p = 0;
        while (p < b.length) {
            long[] r = vint(b, p);
            long tag = r[0];
            p = (int) r[1];
            Field f = new Field();
            f.num = (int) (tag >>> 3);
            f.wire = (int) (tag & 7);
            if (f.wire == 0) {
                r = vint(b, p);
                f.v = r[0];
                p = (int) r[1];
            } else if (f.wire == 2) {
                r = vint(b, p);
                int len = (int) r[0];
                p = (int) r[1];
                if (len < 0 || p + len > b.length) break;
                f.data = Arrays.copyOfRange(b, p, p + len);
                p += len;
            } else if (f.wire == 1) {
                p += 8;
            } else if (f.wire == 5) {
                p += 4;
            } else {
                break;
            }
            out.add(f);
        }
        return out;
    }

    static Field find(List<Field> fs, int num) {
        for (Field f : fs) if (f.num == num) return f;
        return null;
    }
}
