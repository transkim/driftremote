package app.driftremote;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.driftremote.core.Identity;
import app.driftremote.core.RemoteSession;

/**
 * Keeps a control connection to one TV open while the remote screen is visible,
 * reconnecting after Wi-Fi hiccups. Callbacks arrive on the main thread.
 */
public class TvConnection {
    public enum State { CONNECTING, CONNECTED, DISCONNECTED, NEEDS_PAIRING }

    public interface Ui {
        void onState(State state, String detail);

        void onPower(boolean on);

        void onVolume(int level, int max, boolean muted);

        void onTextField(boolean active, String text);
    }

    private final String host;
    private final Ui ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private volatile RemoteSession session;
    private volatile boolean connected;
    private volatile boolean imeField;
    private Thread loop;
    private volatile boolean running;
    private volatile int generation;

    public TvConnection(String host, Ui ui) {
        this.host = host;
        this.ui = ui;
    }

    public boolean isConnected() {
        return connected;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        int gen = ++generation;
        loop = new Thread(() -> runLoop(gen), "tv-connection");
        loop.start();
    }

    public synchronized void stop() {
        running = false;
        generation++;
        RemoteSession s = session;
        if (s != null) s.close();
        if (loop != null) loop.interrupt();
        connected = false;
    }

    private boolean live(int gen) {
        return running && generation == gen;
    }

    private void runLoop(int gen) {
        int delay = 500;
        while (live(gen)) {
            post(State.CONNECTING, null);
            Identity id;
            try {
                id = App.get().identity();
            } catch (Exception e) {
                post(State.DISCONNECTED, "Could not create this phone's key: " + e.getMessage());
                return;
            }
            RemoteSession s = new RemoteSession(host, RemoteSession.PORT, id, new RemoteSession.Listener() {
                @Override public void onReady(String vendor, String model) {}

                @Override
                public void onPowerState(boolean on) {
                    connected = true;
                    main.post(() -> {
                        if (!running) return;
                        ui.onState(State.CONNECTED, null);
                        ui.onPower(on);
                    });
                }

                @Override
                public void onVolume(int level, int max, boolean muted) {
                    main.post(() -> { if (running) ui.onVolume(level, max, muted); });
                }

                @Override public void onCurrentApp(String packageName) {}

                @Override
                public void onImeField(boolean active, String text) {
                    imeField = active;
                    main.post(() -> { if (running) ui.onTextField(active, text); });
                }
            });
            if (!live(gen)) return;
            session = s;
            long startedAt = System.currentTimeMillis();
            try {
                s.run();
            } catch (RemoteSession.NotPairedException e) {
                if (!live(gen)) return;
                connected = false;
                running = false;
                post(State.NEEDS_PAIRING, null);
                return;
            } catch (IOException e) {
                if (!live(gen)) return;
                connected = false;
                post(State.DISCONNECTED, friendly(e));
            }
            if (System.currentTimeMillis() - startedAt > 15000) delay = 500;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
            delay = Math.min(delay * 2, 8000);
        }
    }

    private void post(State st, String detail) {
        main.post(() -> {
            if (running || st == State.NEEDS_PAIRING) ui.onState(st, detail);
        });
    }

    static String friendly(IOException e) {
        String m = e.getMessage();
        if (e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException) {
            return "Can't reach the TV. Is it on, and on the same Wi-Fi?";
        }
        if (e instanceof java.net.SocketTimeoutException) return "The TV isn't answering.";
        return m == null ? "Connection lost." : m;
    }

    // ---- commands (run on a background thread) ----

    public interface Cmd {
        void run(RemoteSession s) throws IOException;
    }

    public boolean send(Cmd cmd) {
        RemoteSession s = session;
        if (!connected || s == null) return false;
        sender.execute(() -> {
            try {
                cmd.run(s);
            } catch (IOException ignored) {
                // The read loop notices a broken connection and reconnects.
            }
        });
        return true;
    }

    public boolean key(int code, int direction) {
        return send(s -> s.key(code, direction));
    }

    public boolean hasTextField() {
        return imeField;
    }

    public boolean supportsText() {
        RemoteSession s = session;
        return s != null && s.supports(RemoteSession.F_IME);
    }
}
