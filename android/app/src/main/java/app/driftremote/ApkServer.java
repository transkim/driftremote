package app.driftremote;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Serves this app's own APK on the Wi-Fi, so the TV can download and install it
 * (the same APK contains the TV mouse service).
 */
public class ApkServer {
    public static final int PORT = 8642;
    private final File apk;
    private ServerSocket server;
    private volatile boolean running;

    public ApkServer(Context ctx) {
        apk = new File(ctx.getApplicationInfo().sourceDir);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                synchronized (this) {
                    server = ss;
                }
                while (running) {
                    Socket s = ss.accept();
                    new Thread(() -> serve(s), "apk-client").start();
                }
            } catch (IOException ignored) {
            }
        }, "apk-server").start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    private void serve(Socket s) {
        try (Socket sock = s; InputStream fin = new FileInputStream(apk)) {
            sock.setSoTimeout(15000);
            InputStream in = sock.getInputStream();
            // read the request headers (we serve the APK for any path)
            int prev = 0, cur, crlf = 0;
            while ((cur = in.read()) >= 0) {
                if (cur == '\n' && prev == '\r') {
                    if (++crlf == 2) break;
                } else if (cur != '\r') {
                    crlf = 0;
                }
                prev = cur;
            }
            OutputStream out = sock.getOutputStream();
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/vnd.android.package-archive\r\n"
                    + "Content-Disposition: attachment; filename=\"driftremote.apk\"\r\n"
                    + "Content-Length: " + apk.length() + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fin.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    /** This phone's Wi-Fi address, or null. */
    public static String phoneIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String name = ni.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (IOException ignored) {
        }
        return fallback;
    }

    public static String url() {
        String ip = phoneIp();
        return ip == null ? null : "http://" + ip + ":" + PORT + "/d.apk";
    }
}
