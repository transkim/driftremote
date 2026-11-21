package app.driftremote.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * TV side of the mouse link. Runs inside the TV's accessibility service and turns
 * phone messages into pointer actions. A phone must type a code shown on the TV once;
 * after that it gets a token and reconnects without asking.
 *
 * <p>Line protocol (UTF-8, one command per line):
 * <pre>
 * phone: HELLO &lt;token|-&gt; &lt;name&gt;      tv: OK &lt;w&gt; &lt;h&gt; | PIN
 * phone: PIN &lt;code&gt;                    tv: TOKEN &lt;token&gt; then OK, or BADPIN
 * phone: M dx dy        move the pointer
 *        C | LP          click | long press at the pointer
 *        DS | DM dx dy | DE   drag: press and hold, move with the pointer, release
 *        SS | SM dx dy | SE   scroll: a finger swipes at the pointer, the pointer stays
 *        PING            tv: PONG
 * </pre>
 */
public final class MouseServer {
    public static final int PORT = 6470;

    public interface Sink {
        void move(float dx, float dy);

        void click();

        void longPress();

        void dragStart();

        void dragMove(float dx, float dy);

        void dragEnd();

        void scrollStart();

        void scrollMove(float dx, float dy);

        void scrollEnd();

        /** Show (or hide, with null) the pairing code on the TV screen. */
        void showCode(String phoneName, String code);

        int[] screenSize();
    }

    public interface Trust {
        boolean isTrusted(String token);

        void addTrusted(String token);
    }

    private final Sink sink;
    private final Trust trust;
    private final int port;
    private volatile ServerSocket server;
    private volatile boolean running;
    private final SecureRandom random = new SecureRandom();

    public MouseServer(int port, Sink sink, Trust trust) {
        this.port = port;
        this.sink = sink;
        this.trust = trust;
    }

    public void start() {
        running = true;
        new Thread(this::acceptLoop, "mouse-server").start();
    }

    public int localPort() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(port));
                server = ss;
                while (running) {
                    Socket s = ss.accept();
                    new Thread(() -> serve(s), "mouse-client").start();
                }
            } catch (IOException e) {
                if (!running) return;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void serve(Socket s) {
        boolean dragging = false, scrolling = false;
        try (Socket sock = s) {
            InetAddress a = sock.getInetAddress();
            if (!(a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress())) return;
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = sock.getOutputStream();

            String hello = in.readLine();
            if (hello == null || !hello.startsWith("HELLO ")) return;
            String[] hp = hello.split(" ", 3);
            String token = hp.length > 1 ? hp[1] : "-";
            String name = hp.length > 2 ? hp[2] : "A phone";
            if (!trust.isTrusted(token)) {
                String code = String.format(Locale.US, "%04d", random.nextInt(10000));
                sink.showCode(name, code);
                send(out, "PIN");
                try {
                    boolean ok = false;
                    for (int tries = 0; tries < 3 && !ok; tries++) {
                        String line = in.readLine();
                        if (line == null) return;
                        if (line.equals("PING")) {
                            send(out, "PONG");
                            tries--;
                            continue;
                        }
                        if (line.startsWith("PIN ") && line.substring(4).trim().equals(code)) {
                            ok = true;
                        } else {
                            send(out, "BADPIN");
                        }
                    }
                    if (!ok) return;
                } finally {
                    sink.showCode(null, null);
                }
                byte[] t = new byte[16];
                random.nextBytes(t);
                StringBuilder sb = new StringBuilder();
                for (byte b : t) sb.append(String.format("%02x", b & 0xff));
                token = sb.toString();
                trust.addTrusted(token);
                send(out, "TOKEN " + token);
            }
            int[] size = sink.screenSize();
            send(out, "OK " + size[0] + " " + size[1]);

            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.split(" ");
                switch (p[0]) {
                    case "M": sink.move(f(p, 1), f(p, 2)); break;
                    case "C": sink.click(); break;
                    case "LP": sink.longPress(); break;
                    case "DS": dragging = true; sink.dragStart(); break;
                    case "DM": sink.dragMove(f(p, 1), f(p, 2)); break;
                    case "DE": dragging = false; sink.dragEnd(); break;
                    case "SS": scrolling = true; sink.scrollStart(); break;
                    case "SM": sink.scrollMove(f(p, 1), f(p, 2)); break;
                    case "SE": scrolling = false; sink.scrollEnd(); break;
                    case "PING": send(out, "PONG"); break;
                    default: break;
                }
            }
        } catch (IOException ignored) {
        } finally {
            // Never leave a finger pressed on the TV if the phone disappears.
            if (dragging) sink.dragEnd();
            if (scrolling) sink.scrollEnd();
        }
    }

    private static float f(String[] p, int i) {
        try {
            return i < p.length ? Float.parseFloat(p[i]) : 0f;
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
