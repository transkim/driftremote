package app.driftremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;

import app.driftremote.core.MouseClient;
import app.driftremote.core.MouseServer;

/** Keeps the phone's mouse link to the TV open while the mouse pad is showing. */
public class MouseLink {
    public enum State { CONNECTING, READY, NEED_PIN, WRONG_PIN, NOT_SET_UP, OFFLINE }

    public interface Ui {
        void onMouseState(State state);
    }

    private final String host;
    private final Ui ui;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile MouseClient client;
    private volatile boolean running;
    private volatile int generation;

    public MouseLink(Context ctx, String host, Ui ui) {
        this.host = host;
        this.ui = ui;
        this.prefs = ctx.getSharedPreferences("driftremote", Context.MODE_PRIVATE);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        int gen = ++generation;
        new Thread(() -> loop(gen), "mouse-link").start();
    }

    public synchronized void stop() {
        running = false;
        generation++;
        MouseClient c = client;
        if (c != null) c.close();
    }

    public MouseClient client() {
        MouseClient c = client;
        return c != null && c.isReady() ? c : null;
    }

    public void sendPin(String code) {
        MouseClient c = client;
        if (c != null) c.sendPin(code);
    }

    private boolean live(int gen) {
        return running && gen == generation;
    }

    private void post(int gen, State s) {
        main.post(() -> {
            if (live(gen)) ui.onMouseState(s);
        });
    }

    private void loop(int gen) {
        int delay = 1000;
        while (live(gen)) {
            post(gen, State.CONNECTING);
            String key = "mouseToken:" + host;
            MouseClient c = new MouseClient(host, MouseServer.PORT, prefs.getString(key, null), App.phoneName(),
                    new MouseClient.Listener() {
                        @Override public void onNeedPin(boolean wrong) { post(gen, wrong ? State.WRONG_PIN : State.NEED_PIN); }
                        @Override public void onToken(String token) { prefs.edit().putString(key, token).apply(); }
                        @Override public void onConnected(int w, int h) { post(gen, State.READY); }
                    });
            client = c;
            if (!live(gen)) return;
            long started = System.currentTimeMillis();
            try {
                c.run();
            } catch (MouseClient.NotInstalledException e) {
                post(gen, State.NOT_SET_UP);
                delay = 3000;
            } catch (IOException | RuntimeException e) {
                if (e instanceof RuntimeException) App.logProblem(e);
                c.close();
                post(gen, State.OFFLINE);
                if (System.currentTimeMillis() - started > 10000) delay = 1000;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
            if (delay < 3000) delay = Math.min(delay * 2, 3000);
        }
    }
}
