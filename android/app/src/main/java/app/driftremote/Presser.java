package app.driftremote;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import app.driftremote.core.RemoteSession;

/**
 * Turns finger presses into key messages. Repeating keys (arrows, volume) repeat while
 * held; other keys send a long press (START_LONG / END_LONG) when held for 600 ms.
 */
public class Presser {
    public interface Sink {
        void key(int code, int direction);
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Sink sink;
    private int key;
    private boolean repeat, longSent;
    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            sink.key(key, RemoteSession.SHORT);
            h.postDelayed(this, 110);
        }
    };
    private final Runnable longPress = () -> {
        longSent = true;
        sink.key(key, RemoteSession.START_LONG);
    };

    public Presser(Sink sink) {
        this.sink = sink;
    }

    public void down(int code, boolean repeating) {
        cancel();
        key = code;
        repeat = repeating;
        longSent = false;
        if (repeating) {
            sink.key(code, RemoteSession.SHORT);
            h.postDelayed(repeater, 400);
        } else {
            h.postDelayed(longPress, 600);
        }
    }

    public void up() {
        if (key == 0) return;
        h.removeCallbacks(repeater);
        h.removeCallbacks(longPress);
        if (!repeat) sink.key(key, longSent ? RemoteSession.END_LONG : RemoteSession.SHORT);
        key = 0;
    }

    public void cancel() {
        h.removeCallbacks(repeater);
        h.removeCallbacks(longPress);
        if (key != 0 && longSent) sink.key(key, RemoteSession.END_LONG);
        key = 0;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void bind(View v, int code, boolean repeating) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    down(code, repeating);
                    break;
                case MotionEvent.ACTION_UP:
                    up();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    cancel();
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}
