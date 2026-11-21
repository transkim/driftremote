package app.driftremote.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.graphics.Path;
import android.os.Build;
import android.os.SystemClock;

/**
 * One virtual finger that can be pressed, moved and lifted over time (for drags
 * and scrolls). On Android 8+ each move is sent as a continuation of the same touch;
 * on Android 7 the whole path is replayed when the finger lifts.
 */
@TargetApi(Build.VERSION_CODES.N)
class Finger {
    private final AccessibilityService service;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Keeps a still finger pressed: re-sends its position while nothing moves. */
    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (busy || !down || stroke == null || Build.VERSION.SDK_INT < 26) return;
            Path p = new Path();
            p.moveTo(sentX, sentY);
            dispatch(Api26.next(stroke, p, 120, true));
        }
    };
    private Object stroke; // GestureDescription.StrokeDescription
    private boolean down, busy, lifting;
    private float sentX, sentY, toX, toY;
    // Android 7 fallback
    private Path path;
    private long downAt;

    Finger(AccessibilityService service) {
        this.service = service;
    }

    void down(float x, float y, long holdMs) {
        if (down) up();
        down = true;
        lifting = false;
        sentX = toX = x;
        sentY = toY = y;
        if (Build.VERSION.SDK_INT >= 26) {
            Path p = new Path();
            p.moveTo(x, y);
            dispatch(Api26.start(p, Math.max(holdMs, 30)));
        } else {
            path = new Path();
            path.moveTo(x, y);
            downAt = SystemClock.uptimeMillis() + holdMs;
        }
    }

    void moveTo(float x, float y) {
        if (!down) return;
        toX = x;
        toY = y;
        if (Build.VERSION.SDK_INT >= 26) {
            pump();
        } else {
            path.lineTo(x, y);
        }
    }

    void up() {
        if (!down) return;
        if (Build.VERSION.SDK_INT >= 26) {
            lifting = true;
            pump();
        } else {
            down = false;
            long duration = Math.max(50, Math.min(SystemClock.uptimeMillis() - downAt + 500, 20000));
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
            service.dispatchGesture(g, null, null);
        }
    }

    private void pump() {
        if (busy || !down || stroke == null) return;
        boolean moved = toX != sentX || toY != sentY;
        if (!moved && !lifting) return;
        Path p = new Path();
        p.moveTo(sentX, sentY);
        if (moved) p.lineTo(toX, toY);
        sentX = toX;
        sentY = toY;
        boolean last = lifting;
        dispatch(Api26.next(stroke, p, last ? 40 : 24, !last));
        if (last) {
            down = false;
            lifting = false;
        }
    }

    private void dispatch(Object s) {
        stroke = s;
        busy = true;
        boolean ok = service.dispatchGesture(Api26.gesture(s), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                busy = false;
                pump();
                handler.removeCallbacks(keepAlive);
                if (down && !busy) handler.postDelayed(keepAlive, 60);
            }

            @Override
            public void onCancelled(GestureDescription d) {
                busy = false;
                down = false;
                lifting = false;
                stroke = null;
            }
        }, null);
        if (!ok) {
            busy = false;
            down = false;
            stroke = null;
        }
    }

    @TargetApi(26)
    private static final class Api26 {
        static Object start(Path p, long duration) {
            return new GestureDescription.StrokeDescription(p, 0, duration, true);
        }

        static Object next(Object prev, Path p, long duration, boolean willContinue) {
            return ((GestureDescription.StrokeDescription) prev).continueStroke(p, 0, duration, willContinue);
        }

        static GestureDescription gesture(Object s) {
            return new GestureDescription.Builder().addStroke((GestureDescription.StrokeDescription) s).build();
        }
    }
}
