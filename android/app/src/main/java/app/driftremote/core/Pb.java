package app.driftremote.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A tiny protobuf encoder/decoder. The remote protocol only uses varints,
 * strings, bytes and nested messages, so a full protobuf runtime isn't needed.
 */
public final class Pb {
    private Pb() {}

    /** Builds one message. Nested messages are written with {@link #msg}. */
    public static final class W {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public W varint(int field, long value) {
            tag(field, 0);
            writeVarint(out, value);
            return this;
        }

        public W bool(int field, boolean value) {
            return varint(field, value ? 1 : 0);
        }

        public W bytes(int field, byte[] value) {
            tag(field, 2);
            writeVarint(out, value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public W str(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        public W msg(int field, W inner) {
            return bytes(field, inner.toBytes());
        }

        private void tag(int field, int wireType) {
            writeVarint(out, ((long) field << 3) | wireType);
        }

        public byte[] toBytes() {
            return out.toByteArray();
        }
    }

    /** Reads the fields of one message in order. */
    public static final class R {
        private final byte[] buf;
        private int pos;
        private final int end;
        public int field;
        public int wireType;
        private long varintValue;
        private int lenStart;
        private int lenEnd;

        public R(byte[] buf) {
            this(buf, 0, buf.length);
        }

        public R(byte[] buf, int start, int end) {
            this.buf = buf;
            this.pos = start;
            this.end = end;
        }

        /** Advances to the next field; returns false at the end of the message. */
        public boolean next() throws IOException {
            if (pos >= end) return false;
            long key = readVarint();
            field = (int) (key >>> 3);
            wireType = (int) (key & 7);
            switch (wireType) {
                case 0:
                    varintValue = readVarint();
                    break;
                case 1:
                    skip(8);
                    break;
                case 2: {
                    int len = (int) readVarint();
                    if (len < 0 || pos + len > end) throw new IOException("bad length");
                    lenStart = pos;
                    lenEnd = pos + len;
                    pos += len;
                    break;
                }
                case 5:
                    skip(4);
                    break;
                default:
                    throw new IOException("unsupported wire type " + wireType);
            }
            return true;
        }

        public long varint() {
            return varintValue;
        }

        public int int32() {
            return (int) varintValue;
        }

        public boolean bool() {
            return varintValue != 0;
        }

        public byte[] bytes() {
            byte[] b = new byte[lenEnd - lenStart];
            System.arraycopy(buf, lenStart, b, 0, b.length);
            return b;
        }

        public String str() {
            return new String(buf, lenStart, lenEnd - lenStart, StandardCharsets.UTF_8);
        }

        public R sub() {
            return new R(buf, lenStart, lenEnd);
        }

        private void skip(int n) throws IOException {
            if (pos + n > end) throw new IOException("truncated");
            pos += n;
        }

        private long readVarint() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new IOException("truncated varint");
                int b = buf[pos++] & 0xff;
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw new IOException("varint too long");
        }
    }

    public static void writeVarint(OutputStream out, long value) {
        try {
            while ((value & ~0x7fL) != 0) {
                out.write((int) ((value & 0x7f) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Writes one message prefixed by its varint length, as both TV ports expect. */
    public static void writeFrame(OutputStream out, byte[] msg) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(msg.length + 5);
        writeVarint(frame, msg.length);
        frame.write(msg, 0, msg.length);
        out.write(frame.toByteArray());
        out.flush();
    }

    /** Reads one varint-length-prefixed message, or returns null at end of stream. */
    public static byte[] readFrame(InputStream in) throws IOException {
        long len = 0;
        for (int shift = 0; ; shift += 7) {
            int b = in.read();
            if (b < 0) {
                if (shift == 0) return null;
                throw new IOException("stream ended inside a length");
            }
            len |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            if (shift > 28) throw new IOException("length too long");
        }
        if (len > 4 * 1024 * 1024) throw new IOException("message too large: " + len);
        byte[] msg = new byte[(int) len];
        int got = 0;
        while (got < msg.length) {
            int n = in.read(msg, got, msg.length - got);
            if (n < 0) throw new IOException("stream ended inside a message");
            got += n;
        }
        return msg;
    }
}
