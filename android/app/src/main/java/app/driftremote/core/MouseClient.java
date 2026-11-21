package app.driftremote.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Phone side of the mouse link (see {@link MouseServer} for the protocol). */
public final class MouseClient {
    public interface Listener {
        /** The TV wants the code it shows on screen. Call {@link #sendPin}. */
        void onNeedPin(boolean wrongCode);

        void onToken(String token);

        void onConnected(int screenW, int screenH);
    }

    /** The TV doesn't run the mouse service (not installed or not turned on). */
    public static final class NotInstalledException extends IOException {
        public NotInstalledException(Throwable cause) {
            super("The mouse isn't set up on the TV.", cause);
        }
    }

    private final String host;
    private final int port;
    private final String token;
    private final String name;
    private final Listener listener;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private volatile Socket socket;
    private volatile boolean closed;
    private volatile boolean ready;

    public MouseClient(String host, int port, String token, String name, Listener listener) {
        this.host = host;
        this.port = port;
        this.token = token == null || token.isEmpty() ? "-" : token;
        this.name = name.replace('\n', ' ');
        this.listener = listener;
    }

    public boolean isReady() {
        return ready;
    }

    /** Connects and runs until the link ends; always ends with an exception. */
    public void run() throws IOException {
        Socket s = new Socket();
        socket = s;
        if (closed) throw new IOException("closed");
        try {
            s.connect(new InetSocketAddress(host, port), 4000);
        } catch (ConnectException e) {
            throw new NotInstalledException(e);
        } catch (SocketTimeoutException e) {
            throw new IOException("The TV isn't answering.", e);
        }
        s.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = s.getOutputStream();
        Thread writer = null;
        try {
            write(out, "HELLO " + token + " " + name);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("PIN")) {
                    listener.onNeedPin(false);
                } else if (line.equals("BADPIN")) {
                    listener.onNeedPin(true);
                } else if (line.startsWith("TOKEN ")) {
                    listener.onToken(line.substring(6).trim());
                } else if (line.startsWith("OK")) {
                    String[] p = line.split(" ");
                    int w = p.length > 2 ? Integer.parseInt(p[1]) : 0;
                    int h = p.length > 2 ? Integer.parseInt(p[2]) : 0;
                    ready = true;
                    listener.onConnected(w, h);
                    if (writer == null) {
                        writer = new Thread(() -> writeLoop(out), "mouse-writer");
                        writer.start();
                    }
                }
                // PONG and unknown lines are ignored
            }
            throw new IOException("The TV closed the mouse link.");
        } finally {
            ready = false;
            close();
            if (writer != null) writer.interrupt();
        }
    }

    private void writeLoop(OutputStream out) {
        try {
            while (!closed) {
                String cmd = queue.poll(5, TimeUnit.SECONDS);
                if (cmd == null) cmd = "PING";
                StringBuilder batch = new StringBuilder(cmd).append('\n');
                String more;
                while ((more = queue.poll()) != null) batch.append(more).append('\n');
                out.write(batch.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException | IOException e) {
            close();
        }
    }

    private synchronized void write(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public void sendPin(String code) {
        Socket s = socket;
        if (s == null) return;
        new Thread(() -> {
            try {
                write(s.getOutputStream(), "PIN " + code.trim());
            } catch (IOException ignored) {
            }
        }, "mouse-pin").start();
    }

    private void cmd(String c) {
        if (ready) queue.offer(c);
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.1f", v);
    }

    public void move(float dx, float dy) { cmd("M " + fmt(dx) + " " + fmt(dy)); }
    public void click() { cmd("C"); }
    public void longPress() { cmd("LP"); }
    public void dragStart() { cmd("DS"); }
    public void dragMove(float dx, float dy) { cmd("DM " + fmt(dx) + " " + fmt(dy)); }
    public void dragEnd() { cmd("DE"); }
    public void scrollStart() { cmd("SS"); }
    public void scrollMove(float dx, float dy) { cmd("SM " + fmt(dx) + " " + fmt(dy)); }
    public void scrollEnd() { cmd("SE"); }

    public void close() {
        closed = true;
        ready = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
