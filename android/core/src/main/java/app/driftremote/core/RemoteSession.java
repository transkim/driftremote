package app.driftremote.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * A control connection to a paired TV on port 6466. {@link #run()} blocks on the
 * calling thread, answering the TV's configure/ping messages and reporting state;
 * the send methods may be called from any other thread.
 */
public final class RemoteSession {
    public static final int PORT = 6466;

    // Feature bits negotiated in remote_configure / remote_set_active
    public static final int F_PING = 1, F_KEY = 2, F_IME = 4, F_POWER = 32, F_VOLUME = 64, F_APP_LINK = 512;

    public static final int SHORT = 3, START_LONG = 1, END_LONG = 2;

    public interface Listener {
        void onReady(String vendor, String model);

        void onPowerState(boolean on);

        void onVolume(int level, int max, boolean muted);

        void onCurrentApp(String packageName);

        void onImeField(boolean active, String text);
    }

    /** Thrown when the TV refuses this phone's certificate: pairing is needed. */
    public static final class NotPairedException extends IOException {
        public NotPairedException(Throwable cause) {
            super("This TV needs to be paired first.", cause);
        }
    }

    private final String host;
    private final int port;
    private final Identity identity;
    private final Listener listener;
    private volatile SSLSocket socket;
    private OutputStream out;
    private volatile boolean closed;
    private int features = F_PING | F_KEY | F_IME | F_POWER | F_VOLUME | F_APP_LINK;
    private int imeCounter;
    private int fieldCounter;
    // Counters from the focused field win over later batch-edit echoes (see androidtvremote2 #51).
    private boolean hasImeCounter, hasFieldCounter;
    private boolean started;

    public RemoteSession(String host, int port, Identity identity, Listener listener) {
        this.host = host;
        this.port = port;
        this.identity = identity;
        this.listener = listener;
    }

    /** Connects and processes messages until the connection ends. Always throws when it ends. */
    public void run() throws IOException {
        SSLSocket s;
        try {
            s = (SSLSocket) identity.sslContext().getSocketFactory().createSocket();
        } catch (Exception e) {
            throw new IOException("TLS setup failed: " + e.getMessage(), e);
        }
        socket = s;
        if (closed) throw new IOException("closed");
        s.connect(new InetSocketAddress(host, port), 5000);
        // The TV pings every 5 s; 20 s of silence means the link is gone.
        s.setSoTimeout(20000);
        s.setTcpNoDelay(true);
        InputStream in;
        try {
            s.startHandshake();
            in = s.getInputStream();
            synchronized (this) {
                out = s.getOutputStream();
            }
        } catch (SSLException e) {
            throw new NotPairedException(e);
        }
        try {
            while (!closed) {
                byte[] raw;
                try {
                    raw = Pb.readFrame(in);
                } catch (SSLException e) {
                    // An unpaired certificate is often rejected right after the handshake.
                    if (!started) throw new NotPairedException(e);
                    throw e;
                } catch (SocketTimeoutException e) {
                    throw new IOException("The TV stopped answering.", e);
                }
                if (raw == null) {
                    if (!started) throw new NotPairedException(null);
                    throw new IOException("The TV closed the connection.");
                }
                handle(raw);
            }
            throw new IOException("closed");
        } finally {
            close();
        }
    }

    private void handle(byte[] raw) throws IOException {
        Pb.R r = new Pb.R(raw);
        while (r.next()) {
            if (r.wireType != 2) continue;
            switch (r.field) {
                case 1: onConfigure(r.sub()); break;
                case 2: send(new Pb.W().msg(2, new Pb.W().varint(1, features))); break;
                case 8: onPing(r.sub()); break;
                case 20: onImeKeyInject(r.sub()); break;
                case 21: onImeBatchEdit(r.sub()); break;
                case 22: onImeShow(r.sub()); break;
                case 40: onStart(r.sub()); break;
                case 50: onVolume(r.sub()); break;
                default: break;
            }
        }
    }

    private void onConfigure(Pb.R r) throws IOException {
        int supported = 0;
        String vendor = "", model = "";
        while (r.next()) {
            if (r.field == 1 && r.wireType == 0) supported = r.int32();
            if (r.field == 2 && r.wireType == 2) {
                Pb.R d = r.sub();
                while (d.next()) {
                    if (d.wireType != 2) continue;
                    if (d.field == 1) model = d.str();
                    if (d.field == 2) vendor = d.str();
                }
            }
        }
        if (supported != 0) features &= supported;
        Pb.W info = new Pb.W().varint(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0");
        send(new Pb.W().msg(1, new Pb.W().varint(1, features).msg(2, info)));
        listener.onReady(vendor, model);
    }

    private void onPing(Pb.R r) throws IOException {
        int val1 = 0;
        while (r.next()) if (r.field == 1 && r.wireType == 0) val1 = r.int32();
        send(new Pb.W().msg(9, new Pb.W().varint(1, val1)));
    }

    private void onStart(Pb.R r) throws IOException {
        boolean on = false;
        while (r.next()) if (r.field == 1 && r.wireType == 0) on = r.bool();
        started = true;
        listener.onPowerState(on);
    }

    private void onVolume(Pb.R r) throws IOException {
        int level = 0, max = 0;
        boolean muted = false;
        while (r.next()) {
            if (r.wireType != 0) continue;
            if (r.field == 6) max = r.int32();
            if (r.field == 7) level = r.int32();
            if (r.field == 8) muted = r.bool();
        }
        listener.onVolume(level, max, muted);
    }

    private void onImeKeyInject(Pb.R r) throws IOException {
        String pkg = null;
        int ime = 0, field = 0;
        String text = null;
        while (r.next()) {
            if (r.wireType != 2) continue;
            Pb.R s = r.sub();
            if (r.field == 1) {
                while (s.next()) {
                    if (s.field == 1 && s.wireType == 0) ime = s.int32();
                    if (s.field == 12 && s.wireType == 2) pkg = s.str();
                }
            } else if (r.field == 2) {
                text = "";
                while (s.next()) {
                    if (s.field == 1 && s.wireType == 0) field = s.int32();
                    if (s.field == 2 && s.wireType == 2) text = s.str();
                }
            }
        }
        imeCounter = ime;
        fieldCounter = field;
        hasImeCounter = ime != 0;
        hasFieldCounter = field != 0;
        if (text != null) listener.onImeField(true, text);
        if (pkg != null) listener.onCurrentApp(pkg);
    }

    private void onImeShow(Pb.R r) throws IOException {
        boolean hasField = false;
        while (r.next()) {
            if (r.field == 2 && r.wireType == 2) {
                hasField = true;
                Pb.R s = r.sub();
                while (s.next()) if (s.field == 1 && s.wireType == 0) fieldCounter = s.int32();
                hasFieldCounter = true;
            }
        }
        listener.onImeField(hasField, "");
    }

    private void onImeBatchEdit(Pb.R r) throws IOException {
        while (r.next()) {
            if (r.wireType != 0) continue;
            if (r.field == 1 && !hasImeCounter) imeCounter = r.int32();
            if (r.field == 2 && !hasFieldCounter) fieldCounter = r.int32();
        }
    }

    // ---- commands ----

    public void key(int keyCode, int direction) throws IOException {
        send(new Pb.W().msg(10, new Pb.W().varint(1, keyCode).varint(2, direction)));
    }

    /** Types text into the focused text field on the TV. */
    public void text(String text) throws IOException {
        Pb.W obj = new Pb.W().varint(1, text.length()).varint(2, text.length()).str(3, text);
        Pb.W edit = new Pb.W().varint(1, 1).msg(2, obj);
        send(new Pb.W().msg(21, new Pb.W().varint(1, imeCounter).varint(2, fieldCounter).msg(3, edit)));
    }

    /** Opens an app link such as https://www.youtube.com or market://launch?id=package. */
    public void openLink(String link) throws IOException {
        send(new Pb.W().msg(90, new Pb.W().str(1, link)));
    }

    int[] imeCounters() {
        return new int[]{imeCounter, fieldCounter};
    }

    public boolean supports(int feature) {
        return (features & feature) != 0;
    }

    private synchronized void send(Pb.W msg) throws IOException {
        if (out == null) throw new IOException("Not connected yet.");
        Pb.writeFrame(out, msg.toBytes());
    }

    public void close() {
        closed = true;
        SSLSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
