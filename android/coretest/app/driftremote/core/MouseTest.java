package app.driftremote.core;

import java.util.*;
import java.util.concurrent.*;

/** Runs the phone mouse client against the TV mouse server with a recording sink. */
public class MouseTest {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        System.out.println("ok - " + what);
    }

    public static void main(String[] a) {
        try { run(); System.out.println("ALL PASSED"); System.exit(0); }
        catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    }

    static void run() throws Exception {
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        String[] code = new String[1];
        Set<String> trusted = Collections.synchronizedSet(new HashSet<>());
        MouseServer.Sink sink = new MouseServer.Sink() {
            public void move(float dx, float dy) { got.add("move " + dx + " " + dy); }
            public void click() { got.add("click"); }
            public void longPress() { got.add("long"); }
            public void dragStart() { got.add("ds"); }
            public void dragMove(float dx, float dy) { got.add("dm " + dx + " " + dy); }
            public void dragEnd() { got.add("de"); }
            public void scrollStart() { got.add("ss"); }
            public void scrollMove(float dx, float dy) { got.add("sm " + dx + " " + dy); }
            public void scrollEnd() { got.add("se"); }
            public void showCode(String n, String c) { code[0] = c; if (c != null) got.add("code shown for " + n); }
            public int[] screenSize() { return new int[]{1920, 1080}; }
        };
        MouseServer.Trust trust = new MouseServer.Trust() {
            public boolean isTrusted(String t) { return trusted.contains(t); }
            public void addTrusted(String t) { trusted.add(t); }
        };
        MouseServer server = new MouseServer(0, sink, trust);
        server.start();
        int port = -1;
        for (int i = 0; i < 50 && port <= 0; i++) { Thread.sleep(50); port = server.localPort(); }
        check(port > 0, "server listening on " + port);

        // first connection: wrong code then right code
        String[] token = new String[1];
        CountDownLatch connected = new CountDownLatch(1);
        int[] pinAsks = {0};
        MouseClient[] c = new MouseClient[1];
        c[0] = new MouseClient("127.0.0.1", port, null, "Test phone", new MouseClient.Listener() {
            public void onNeedPin(boolean wrong) {
                pinAsks[0]++;
                c[0].sendPin(wrong ? code[0] : "99999");
            }
            public void onToken(String t) { token[0] = t; }
            public void onConnected(int w, int h) { if (w == 1920 && h == 1080) connected.countDown(); }
        });
        Thread t = new Thread(() -> { try { c[0].run(); } catch (Exception e) { } });
        t.start();
        check(connected.await(5, TimeUnit.SECONDS), "paired with code and connected");
        check(pinAsks[0] == 2, "wrong code was refused, right code accepted");
        check(token[0] != null && token[0].length() == 32, "got a token");
        check(got.contains("code shown for Test phone"), "TV showed the code");
        check(code[0] == null, "TV hid the code after pairing");

        c[0].move(10.5f, -3f);
        c[0].click();
        c[0].dragStart(); c[0].dragMove(4, 5); c[0].dragEnd();
        c[0].scrollStart(); c[0].scrollMove(0, -20); c[0].scrollEnd();
        c[0].longPress();
        Thread.sleep(500);
        check(got.contains("move 10.5 -3.0"), "move");
        check(got.contains("click"), "click");
        check(got.indexOf("ds") < got.indexOf("dm 4.0 5.0") && got.indexOf("dm 4.0 5.0") < got.indexOf("de"), "drag in order");
        check(got.contains("sm 0.0 -20.0") && got.contains("se"), "scroll");
        check(got.contains("long"), "long press");

        // drop the link mid-drag: server must release the finger
        got.clear();
        c[0].dragStart();
        Thread.sleep(200);
        c[0].close();
        t.join(2000);
        Thread.sleep(300);
        check(got.contains("ds") && got.contains("de"), "drag released when the phone disconnects");

        // second connection with the token: no code needed
        got.clear();
        CountDownLatch again = new CountDownLatch(1);
        MouseClient c2 = new MouseClient("127.0.0.1", port, token[0], "Test phone", new MouseClient.Listener() {
            public void onNeedPin(boolean wrong) { got.add("asked pin"); }
            public void onToken(String tk) {}
            public void onConnected(int w, int h) { again.countDown(); }
        });
        new Thread(() -> { try { c2.run(); } catch (Exception e) { } }).start();
        check(again.await(5, TimeUnit.SECONDS) && !got.contains("asked pin"), "reconnect with token skips the code");
        c2.close();

        // nothing listening -> NotInstalledException
        server.stop();
        Thread.sleep(200);
        try {
            new MouseClient("127.0.0.1", port, null, "x", null).run();
            check(false, "should fail");
        } catch (MouseClient.NotInstalledException e) {
            check(true, "no mouse service -> NotInstalledException");
        }
    }
}
